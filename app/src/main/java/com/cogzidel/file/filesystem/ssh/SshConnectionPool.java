/*
 * SshConnectionPool.java
 *
 * Copyright © 2017 Raymond Lai <airwave209gt at gmail.com>.
 *
 * This file is part of AmazeFileManager.
 *
 * AmazeFileManager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AmazeFileManager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AmazeFileManager. If not, see <http ://www.gnu.org/licenses/>.
 */

package com.cogzidel.file.filesystem.ssh;

import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.cogzidel.file.activities.MainActivity;
import com.cogzidel.file.database.UtilsHandler;
import com.cogzidel.file.asynchronous.asynctasks.AsyncTaskResult;
import com.cogzidel.file.asynchronous.asynctasks.ssh.PemToKeyPairTask;
import com.cogzidel.file.asynchronous.asynctasks.ssh.SshAuthenticationTask;
import com.cogzidel.file.utils.application.AppConfig;

import net.schmizz.sshj.SSHClient;

import java.io.IOException;
import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Poor man's implementation of SSH connection pool.
 *
 * It uses a {@link ConcurrentHashMap} to hold the opened SSH connections; all code that uses
 * {@link SSHClient} can ask for connection here with <code>getConnection(url)</code>.
 */
public class SshConnectionPool
{
    public static final int SSH_DEFAULT_PORT = 22;

    public static final String SSH_URI_PREFIX = "ssh://";

    public static final int SSH_CONNECT_TIMEOUT = 30000;

    private static final String TAG = "SshConnectionPool";

    private static SshConnectionPool instance = null;

    private final Map<String, SSHClient> connections;

    private SshConnectionPool()
    {
        connections = new ConcurrentHashMap<String, SSHClient>();
    }

    /**
     * Use this to obtain SshConnectionPool instance singleton.
     *
     * @return {@link SshConnectionPool} instance
     */
    public static final SshConnectionPool getInstance() {
        if(instance == null)
            instance = new SshConnectionPool();

        return instance;
    }

    /**
     * Obtain a {@link SSHClient} connection from the underlying connection pool.
     *
     * Beneath it will return the connection if it exists; otherwise it will create a new one and
     * put it into the connection pool.
     *
     * @param url SSH connection URL, in the form of <code>ssh://&lt;username&gt;:&lt;password&gt;@&lt;host&gt;:&lt;port&gt;</code> or <code>ssh://&lt;username&gt;@&lt;host&gt;:&lt;port&gt;</code>
     * @return {@link SSHClient} connection, already opened and authenticated
     * @throws IOException IOExceptions that occur during connection setup
     */
    public SSHClient getConnection(@NonNull String url)  {
        url = SshClientUtils.extractBaseUriFrom(url);

        SSHClient client = connections.get(url);
        if(client == null) {
            client = create(url);
            if(client != null)
                connections.put(url, client);
        } else {
            if(!validate(client)) {
                Log.d(TAG, "Connection no longer usable. Reconnecting...");
                expire(client);
                connections.remove(url);
                client = create(url);
                if(client != null)
                    connections.put(url, client);
            }
        }
        return client;
    }

    /**
     * Obtain a {@link SSHClient} connection from the underlying connection pool.
     *
     * Beneath it will return the connection if it exists; otherwise it will create a new one and
     * put it into the connection pool.
     *
     * Different from {@link #getConnection(String)} above, this accepts broken down parameters as
     * convenience method during setting up SCP/SFTP connection.
     *
     * @param host host name/IP, required
     * @param port SSH server port, required
     * @param hostFingerprint expected host fingerprint, required
     * @param username username, required
     * @param password password, required if using password to authenticate
     * @param keyPair {@link KeyPair}, required if using key-based authentication
     * @return {@link SSHClient} connection
     */
    public SSHClient getConnection(@NonNull String host, int port, @NonNull String hostFingerprint,
                                   @NonNull String username, @Nullable String password,
                                   @Nullable KeyPair keyPair) {

        String url = SshClientUtils.deriveSftpPathFrom(host, port, username, password, keyPair);

        SSHClient client = connections.get(url);
        if(client == null) {
            client = create(host, port, hostFingerprint, username, password, keyPair);
            if(client != null)
                connections.put(url, client);
        } else {
            if(!validate(client)) {
                Log.d(TAG, "Connection no longer usable. Reconnecting...");
                expire(client);
                connections.remove(url);
                client = create(host, port, hostFingerprint, username, password, keyPair);
                if(client != null)
                    connections.put(url, client);
            }
        }
        return client;
    }

    /**
     * Kill any connection that is still in place. Used by {@link com.cogzidel.file.activities.MainActivity}.
     *
     * @see MainActivity#onDestroy()
     * @see MainActivity#exit()
     */
    public void expungeAllConnections() {
        AppConfig.runInBackground(() -> {
            if(!connections.isEmpty()) {
                for (SSHClient connection : connections.values()) {
                    SshClientUtils.tryDisconnect(connection);
                }
                connections.clear();
            }
        });
    }

    private SSHClient create(@NonNull String url) {
        return create(Uri.parse(url));
    }

    // Logic for creating SSH connection. Depends on password existence in given Uri password or
    // key-based authentication
    private SSHClient create(@NonNull Uri uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        //If the uri is fetched from the app's database storage, we assume it will never be empty
        String[] userInfo = uri.getUserInfo().split(":");
        String username = userInfo[0];
        String password = userInfo.length > 1 ? userInfo[1] : null;

        if(port < 0)
            port = SSH_DEFAULT_PORT;

        UtilsHandler utilsHandler = AppConfig.getInstance().getUtilsHandler();
        String pem = utilsHandler.getSshAuthPrivateKey(uri.toString());

        AtomicReference<KeyPair> keyPair = new AtomicReference<>(null);;
        if(pem != null && !pem.isEmpty()) {
            try {
                CountDownLatch latch = new CountDownLatch(1);
                new PemToKeyPairTask(pem, result -> {
                    if (result.result != null) {
                        keyPair.set(result.result);
                        latch.countDown();
                    }
                }).execute();
                latch.await();
            } catch(InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        return create(host, port, utilsHandler.getSshHostKey(uri.toString()), username, password,
                keyPair.get());
    }

    private SSHClient create(@NonNull String host, int port, @NonNull String hostKey,
                             @NonNull String username, @Nullable String password,
                             @Nullable KeyPair keyPair) {

        try {
            AsyncTaskResult<SSHClient> taskResult = new SshAuthenticationTask(host, port,
                    hostKey, username, password, keyPair).execute().get();

            SSHClient client = taskResult.result;
            return client;
        } catch(InterruptedException e) {
            //FIXME: proper handling
            throw new RuntimeException(e);
        } catch(ExecutionException e) {
            //FIXME: proper handling
            throw new RuntimeException(e);
        }
    }

    private boolean validate(@NonNull SSHClient client) {
        return client.isConnected() && client.isAuthenticated();
    }

    private void expire(@NonNull SSHClient client) {
        SshClientUtils.tryDisconnect(client);
    }
}
