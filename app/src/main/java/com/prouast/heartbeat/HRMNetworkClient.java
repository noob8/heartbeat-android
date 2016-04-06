package com.prouast.heartbeat;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Connects to an TCP server running on an instance of a Brownie client
 * Implements runnable and should be run on its own Thread
 * @author prouast
 */
public class HRMNetworkClient implements Runnable {

    private static final String TAG = "Heartbeat::HRMNetClient";
    private static final int SERVER_PORT = 8080;

    private String serverAddress;
    private NetworkClientStateListener listener;
    private InputStream is;
    private OutputStream os;
    private DataInputStream dis;
    private DataOutputStream dos;

    public volatile boolean isActive;

    /**
     * This listener can be implemented to be notified of state changes.
     */
    public interface NetworkClientStateListener {
        void onNetworkTryingToConnect();
        void onNetworkConnected();
        void onNetworkDisconnected();
        void onNetworkConnectException();
    }

    /**
     * Constructor for a new HRMNetworkClient.
     * @param listener The listener
     */
    public HRMNetworkClient(NetworkClientStateListener listener) {
        this.isActive = false;
        this.listener = listener;
    }

    /**
     * Set the IP address of the TCP server.
     * @param serverAddress The server address
     */
    public synchronized void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    /**
     * Implementation of the Runnable interface
     * Manages the connection of TCP client to server from start to end
     */
    public void run() {

        isActive = true;
        Thread.currentThread().setName("NetworkClientThread");

        try {

            // Create socket
            listener.onNetworkTryingToConnect();
            Socket socket = new Socket(serverAddress, SERVER_PORT);
            Log.i(TAG, "Connection established to " + socket.getRemoteSocketAddress() + " on port " + socket.getLocalPort());

            try {

                // Set up streams
                is = socket.getInputStream();
                os = socket.getOutputStream();
                dis = new DataInputStream(is);
                dos = new DataOutputStream(os);

                // Notify listener that connection to server was successful
                listener.onNetworkConnected();

                // Start thread for sending HRMResults when available from HRMResultQueue
                Thread thread = new Thread() {
                    HRMResultQueue queue = HRMResultQueue.getInstance();
                    public void run() {
                        HRMResult result;
                        while (isActive) {
                            result = queue.pop();
                            try {
                                sendHeartrate(result);
                                Log.i(TAG, "Sending the HR…");
                            } catch (IOException e) {
                                Log.e(TAG, "Exception while sending the HR: " + e);
                            }
                        }
                    }
                };
                thread.setName("HRMResultDeliveryThread");
                thread.start();

                // Main loop listens to messages from the server
                while (isActive) {

                    // Read length of the message
                    int len = dis.readInt();
                    if (len > 0) {

                        // Store time when message was received
                        long timeReceivedByClient = System.currentTimeMillis();

                        // Read type of the message
                        HRMNetworkMessageType type = HRMNetworkMessageType.fromInt(dis.readInt());

                        switch (type) {

                            // Received a heartbeat request
                            case HEARTBEAT:
                                Log.i(TAG, "Received heartbeat request");
                                sendMsg(new byte[]{0}, HRMNetworkMessageType.HEARTBEAT);
                                Log.i(TAG, "Answered heartbeat request");
                                break;

                            // Received a time synchronisation request
                            case TIME_SYNC:
                                Log.i(TAG, "Received time synchronisation request");
                                long timeSentByServer = dis.readLong();
                                respondTimeSync(timeReceivedByClient - timeSentByServer);
                                Log.i(TAG, "Answered time synchronisation request");
                                break;

                            // Received a stop request
                            case STOP:
                                Log.i(TAG, "Received stop request");
                                sendMsg(new byte[]{0}, HRMNetworkMessageType.STOP);
                                Log.i(TAG, "Answered stop request");
                                isActive = false;
                                break;

                            default:
                                Log.e(TAG, "Message content not recognised!");
                                break;
                        }
                    }
                }
            } catch (EOFException e) {
                Log.e(TAG, "Exception reading DataInputStream: " + e);
            } catch (Exception e) {
                Log.e(TAG, "Exception communicating with server: ", e);
            } finally {
                Log.i(TAG, "Stopping the client…");
                isActive = false;
                is.close();
                os.close();
                dis.close();
                dos.close();
                socket.close();
                listener.onNetworkDisconnected();
            }
        } catch (ConnectException e) {
            Log.e(TAG, "Exception while connecting to server. False IP address or no server running?: ", e);
            listener.onNetworkConnectException();
        } catch (Exception e) {
            Log.e(TAG, "Exception creating socket: ", e);
            listener.onNetworkConnectException();
        } finally {
            isActive = false;
        }
    }

    /**
     * Send a message to server containing the heartrate and timestamp
     * @param result The HRMResult
     * @throws IOException
     */
    private void sendHeartrate(HRMResult result) throws IOException {
        byte[] mean = toByteArray(result.getMean());
        byte[] min = toByteArray(result.getMin());
        byte[] max = toByteArray(result.getMax());
        byte[] time = toByteArray(result.getTime());
        byte[] msg = new byte[mean.length + min.length + max.length + time.length];
        System.arraycopy(mean, 0, msg, 0,                                     mean.length);
        System.arraycopy(min,  0, msg, mean.length,                           min.length);
        System.arraycopy(max,  0, msg, mean.length + min.length,              max.length);
        System.arraycopy(time, 0, msg, mean.length + min.length + max.length, time.length);
        sendMsg(msg, HRMNetworkMessageType.HEARTRATE);
    }

    /**
     * Send a message to server containing the original offset and new timestamp
     * @param offset The offset calculated from time sync request
     * @throws IOException
     */
    private void respondTimeSync(long offset) throws IOException {
        byte[] offset1 = toByteArray(offset);
        byte[] tClient = toByteArray(System.currentTimeMillis());
        byte[] msg = new byte[offset1.length + tClient.length];
        System.arraycopy(offset1, 0, msg, 0,              offset1.length);
        System.arraycopy(tClient, 0, msg, offset1.length, tClient.length);
        sendMsg(msg, HRMNetworkMessageType.TIME_SYNC);
    }

    /**
     * Generic method to send a message to the server
     * @param msg The message encoded as byte array
     * @param type The message type
     * @throws IOException
     */
    private synchronized void sendMsg(byte[] msg, HRMNetworkMessageType type) throws IOException {

        if (msg == null)
            throw new IllegalArgumentException("Message cannot be null!");
        if (msg.length == 0)
            throw new IllegalArgumentException("Message should have content!");

        dos.writeInt(msg.length);
        dos.writeInt(type.ordinal());
        dos.write(msg, 0, msg.length);
    }

    /**
     * Convert a double to a byte array representation.
     * @param value double
     * @return byte array
     */
    private static byte[] toByteArray(double value) {
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putDouble(value);
        return bytes;
    }

    /**
     * Convert a byte array representation to double.
     * @param value byte array
     * @return double
     */
    private static byte[] toByteArray(long value) {
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(value);
        return bytes;
    }

    /**
     * Stop the network client.
     */
    public synchronized void stop() {
        this.isActive = false;
    }
}