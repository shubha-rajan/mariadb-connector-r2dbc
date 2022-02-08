// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2020-2022 MariaDB Corporation Ab

package org.mariadb.r2dbc;

import io.netty.channel.unix.DomainSocketAddress;
import io.r2dbc.spi.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map;
import org.mariadb.r2dbc.client.Client;
import org.mariadb.r2dbc.client.ClientImpl;
import org.mariadb.r2dbc.client.ClientPipelineImpl;
import org.mariadb.r2dbc.message.flow.AuthenticationFlow;
import org.mariadb.r2dbc.util.Assert;
import org.mariadb.r2dbc.util.HostAddress;
import org.mariadb.r2dbc.util.constants.Capabilities;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

public final class MariadbConnectionFactory implements ConnectionFactory {

  private final MariadbConnectionConfiguration configuration;

  public MariadbConnectionFactory(MariadbConnectionConfiguration configuration) {
    this.configuration = Assert.requireNonNull(configuration, "configuration must not be null");
  }

  public static MariadbConnectionFactory from(MariadbConnectionConfiguration configuration) {
    return new MariadbConnectionFactory(configuration);
  }

  @Override
  public Mono<org.mariadb.r2dbc.api.MariadbConnection> create() {
    if (configuration.getSocket() != null) {
      return connectToHost(new DomainSocketAddress(configuration.getSocket()), null)
          .cast(org.mariadb.r2dbc.api.MariadbConnection.class);
    } else {
      return doCreateConnection(0).cast(org.mariadb.r2dbc.api.MariadbConnection.class);
    }
  }

  private Mono<MariadbConnection> doCreateConnection(final int idx) {
    HostAddress hostAddress = configuration.getHostAddresses().get(idx);
    return connectToHost(
            InetSocketAddress.createUnresolved(hostAddress.getHost(), hostAddress.getPort()),
            hostAddress)
        .onErrorResume(
            e -> {
              if (idx + 1 < configuration.getHostAddresses().size()) {
                return doCreateConnection(idx + 1);
              }
              return Mono.error(e);
            });
  }

  private Mono<MariadbConnection> connectToHost(SocketAddress endpoint, HostAddress hostAddress) {
    Mono<Client> clientMono;
    if (configuration.allowPipelining()) {
      clientMono =
          ClientPipelineImpl.connect(
              ConnectionProvider.newConnection(), endpoint, hostAddress, configuration);
    } else {
      clientMono =
          ClientImpl.connect(
              ConnectionProvider.newConnection(), endpoint, hostAddress, configuration);
    }
    return clientMono
        .delayUntil(client -> AuthenticationFlow.exchange(client, this.configuration, hostAddress))
        .cast(Client.class)
        .flatMap(
            client ->
                setSessionVariables(client)
                    .then(
                        Mono.just(
                            new MariadbConnection(
                                client,
                                configuration.getIsolationLevel() == null
                                    ? IsolationLevel.REPEATABLE_READ
                                    : configuration.getIsolationLevel(),
                                configuration)))
                    .onErrorResume(throwable -> this.closeWithError(client, throwable)))
        .onErrorMap(e -> cannotConnect(e, endpoint));
  }

  private Mono<MariadbConnection> closeWithError(Client client, Throwable throwable) {
    return client.close().then(Mono.error(throwable));
  }

  private Throwable cannotConnect(Throwable throwable, SocketAddress endpoint) {

    if (throwable instanceof R2dbcException) {
      return throwable;
    }

    return new R2dbcNonTransientResourceException(
        String.format("Cannot connect to %s", endpoint), throwable);
  }

  @Override
  public ConnectionFactoryMetadata getMetadata() {
    return MariadbConnectionFactoryMetadata.INSTANCE;
  }

  @Override
  public String toString() {
    return "MariadbConnectionFactory{configuration=" + this.configuration + '}';
  }

  private Mono<Void> setSessionVariables(Client client) {

    // set default autocommit value
    StringBuilder sql =
        new StringBuilder("SET autocommit=" + (configuration.autocommit() ? "1" : "0"));

    // set default transaction isolation
    String txIsolation =
        (!client.getVersion().isMariaDBServer()
                && (client.getVersion().versionGreaterOrEqual(8, 0, 3)
                    || (client.getVersion().getMajorVersion() < 8
                        && client.getVersion().versionGreaterOrEqual(5, 7, 20))))
            ? "transaction_isolation"
            : "tx_isolation";
    sql.append(",")
        .append(txIsolation)
        .append("='")
        .append(
            configuration.getIsolationLevel() == null
                ? "REPEATABLE-READ"
                : configuration.getIsolationLevel().asSql().replace(" ", "-"))
        .append("'");

    // set session tracking
    if ((client.getContext().getClientCapabilities() & Capabilities.CLIENT_SESSION_TRACK) > 0) {
      sql.append(",session_track_schema=1");
      sql.append(",session_track_system_variables='autocommit,").append(txIsolation).append("'");
    }

    // set session variables if defined
    if (configuration.getSessionVariables() != null
        && configuration.getSessionVariables().size() > 0) {
      Map<String, String> sessionVariable = configuration.getSessionVariables();
      Iterator<String> keys = sessionVariable.keySet().iterator();
      for (int i = 0; i < sessionVariable.size(); i++) {
        String key = keys.next();
        String value = sessionVariable.get(key);
        if (value == null)
          throw new IllegalArgumentException(
              String.format("Session variable '%s' has no value", key));
        sql.append(",").append(key).append("=").append(value);
      }
    }

    return client.executeSimpleCommand(sql.toString()).last().then();
  }
}
