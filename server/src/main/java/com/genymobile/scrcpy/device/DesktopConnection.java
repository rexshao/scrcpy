package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME_PREFIX = "scrcpy";

    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;

    private final Socket videoTcpSocket;

    private final LocalSocket audioSocket;
    private final FileDescriptor audioFd;

    private final Socket audioTcpSocket;

    private final LocalSocket controlSocket;
    private final ControlChannel controlChannel;

    private final Socket controlTcpSocket;

    private DesktopConnection(LocalSocket videoSocket, LocalSocket audioSocket, LocalSocket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;
        this.videoTcpSocket = null;
        this.audioTcpSocket = null;
        this.controlTcpSocket = null;

        videoFd = videoSocket != null ? videoSocket.getFileDescriptor() : null;
        audioFd = audioSocket != null ? audioSocket.getFileDescriptor() : null;
        controlChannel = controlSocket != null ? new ControlChannel(controlSocket) : null;
    }

    private DesktopConnection(Socket videoTcpSocket, Socket audioTcpSocket, Socket controlTcpSocket) throws IOException {
        this.videoTcpSocket = videoTcpSocket;
        this.audioTcpSocket = audioTcpSocket;
        this.controlTcpSocket = controlTcpSocket;

        this.videoSocket = null;
        this.audioSocket = null;
        this.controlSocket = null;

        this.videoFd = null;
        this.audioFd = null;

        controlChannel = controlTcpSocket != null ? new ControlChannel(controlTcpSocket.getInputStream(), controlTcpSocket.getOutputStream()) : null;
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    private static Socket connectTcp(String host, int port) throws IOException {
        return new Socket(host, port);
    }

    private static String getSocketName(int scid) {
        if (scid == -1) {
            // If no SCID is set, use "scrcpy" to simplify using scrcpy-server alone
            return SOCKET_NAME_PREFIX;
        }

        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, int listenPort, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        String socketName = getSocketName(scid);

        // Local sockets
        LocalSocket videoSocket = null;
        LocalSocket audioSocket = null;
        LocalSocket controlSocket = null;

        // TCP sockets
        Socket videoTcpSocket = null;
        Socket audioTcpSocket = null;
        Socket controlTcpSocket = null;

        try {
            if (listenPort > 0) {
                if (tunnelForward) {
                    try (ServerSocket server = new ServerSocket(listenPort)) {
                        if (video) {
                            videoTcpSocket = server.accept();
                            if (sendDummyByte) {
                                videoTcpSocket.getOutputStream().write(0);
                                sendDummyByte = false;
                            }
                        }
                        if (audio) {
                            audioTcpSocket = server.accept();
                            if (sendDummyByte) {
                                audioTcpSocket.getOutputStream().write(0);
                                sendDummyByte = false;
                            }
                        }
                        if (control) {
                            controlTcpSocket = server.accept();
                            if (sendDummyByte) {
                                controlTcpSocket.getOutputStream().write(0);
                                sendDummyByte = false;
                            }
                        }
                    }
                } else {
                    if (video) {
                        videoTcpSocket = connectTcp("127.0.0.1", listenPort);
                    }
                    if (audio) {
                        audioTcpSocket = connectTcp("127.0.0.1", listenPort);
                    }
                    if (control) {
                        controlTcpSocket = connectTcp("127.0.0.1", listenPort);
                    }
                }
                return new DesktopConnection(videoTcpSocket, audioTcpSocket, controlTcpSocket);
            }

            if (tunnelForward) {
                try (LocalServerSocket localServerSocket = new LocalServerSocket(socketName)) {
                    if (video) {
                        videoSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            videoSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            audioSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            controlSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                }
            } else {
                if (video) {
                    videoSocket = connect(socketName);
                }
                if (audio) {
                    audioSocket = connect(socketName);
                }
                if (control) {
                    controlSocket = connect(socketName);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                try {
                    videoSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (audioSocket != null) {
                try {
                    audioSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (controlSocket != null) {
                try {
                    controlSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (videoTcpSocket != null) {
                try {
                    videoTcpSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (audioTcpSocket != null) {
                try {
                    audioTcpSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (controlTcpSocket != null) {
                try {
                    controlTcpSocket.close();
                } catch (IOException ignored) {
                }
            }
            throw e;
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    private LocalSocket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    private Socket getFirstTcpSocket() {
        if (videoTcpSocket != null) {
            return videoTcpSocket;
        }
        if (audioTcpSocket != null) {
            return audioTcpSocket;
        }
        return controlTcpSocket;
    }

    public void shutdown() throws IOException {
        if (videoSocket != null) {
            videoSocket.shutdownInput();
            videoSocket.shutdownOutput();
        }
        if (audioSocket != null) {
            audioSocket.shutdownInput();
            audioSocket.shutdownOutput();
        }
        if (controlSocket != null) {
            controlSocket.shutdownInput();
            controlSocket.shutdownOutput();
        }
        if (videoTcpSocket != null) {
            videoTcpSocket.shutdownInput();
            videoTcpSocket.shutdownOutput();
        }
        if (audioTcpSocket != null) {
            audioTcpSocket.shutdownInput();
            audioTcpSocket.shutdownOutput();
        }
        if (controlTcpSocket != null) {
            controlTcpSocket.shutdownInput();
            controlTcpSocket.shutdownOutput();
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
        if (videoTcpSocket != null) {
            videoTcpSocket.close();
        }
        if (audioTcpSocket != null) {
            audioTcpSocket.close();
        }
        if (controlTcpSocket != null) {
            controlTcpSocket.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        LocalSocket socket = getFirstSocket();
        if (socket != null) {
            FileDescriptor fd = socket.getFileDescriptor();
            IO.writeFully(fd, buffer, 0, buffer.length);
            return;
        }

        Socket tcp = getFirstTcpSocket();
        if (tcp != null) {
            OutputStream out = tcp.getOutputStream();
            IO.writeFully(out, buffer, 0, buffer.length);
        }
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public java.io.OutputStream getVideoOutputStream() throws IOException {
        if (videoTcpSocket != null) {
            return videoTcpSocket.getOutputStream();
        }
        if (videoSocket != null) {
            return videoSocket.getOutputStream();
        }
        return null;
    }

    public java.io.OutputStream getAudioOutputStream() throws IOException {
        if (audioTcpSocket != null) {
            return audioTcpSocket.getOutputStream();
        }
        if (audioSocket != null) {
            return audioSocket.getOutputStream();
        }
        return null;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
