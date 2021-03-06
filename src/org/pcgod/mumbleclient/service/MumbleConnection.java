package org.pcgod.mumbleclient.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import junit.framework.Assert;
import net.sf.mumble.MumbleProto.Authenticate;
import net.sf.mumble.MumbleProto.ChannelRemove;
import net.sf.mumble.MumbleProto.ChannelState;
import net.sf.mumble.MumbleProto.CodecVersion;
import net.sf.mumble.MumbleProto.CryptSetup;
import net.sf.mumble.MumbleProto.Reject;
import net.sf.mumble.MumbleProto.ServerSync;
import net.sf.mumble.MumbleProto.TextMessage;
import net.sf.mumble.MumbleProto.UserRemove;
import net.sf.mumble.MumbleProto.UserState;
import net.sf.mumble.MumbleProto.Version;

import org.pcgod.mumbleclient.Globals;
import org.pcgod.mumbleclient.service.MumbleConnectionHost.ConnectionState;
import org.pcgod.mumbleclient.service.audio.AudioOutput;
import org.pcgod.mumbleclient.service.audio.AudioOutputHost;
import org.pcgod.mumbleclient.service.model.Channel;
import org.pcgod.mumbleclient.service.model.Message;
import org.pcgod.mumbleclient.service.model.TalkingState;
import org.pcgod.mumbleclient.service.model.User;

import android.content.Context;
import android.util.Log;

import com.google.protobuf.MessageLite;

/**
 * The main mumble client connection
 *
 * Maintains connection to the server and implements the low level
 * communication protocol
 *
 * @author pcgod
 */
public class MumbleConnection implements Runnable {
	public enum MessageType {
		Version, UDPTunnel, Authenticate, Ping, Reject, ServerSync, ChannelRemove, ChannelState, UserRemove, UserState, BanList, TextMessage, PermissionDenied, ACL, QueryUsers, CryptSetup, ContextActionAdd, ContextAction, UserList, VoiceTarget, PermissionQuery, CodecVersion, UserStats, RequestBlob, ServerConfig
	}

	public static final int UDPMESSAGETYPE_UDPVOICECELTALPHA = 0;
	public static final int UDPMESSAGETYPE_UDPPING = 1;
	public static final int UDPMESSAGETYPE_UDPVOICESPEEX = 2;
	public static final int UDPMESSAGETYPE_UDPVOICECELTBETA = 3;

	public static final int CODEC_NOCODEC = -1;
	public static final int CODEC_ALPHA = UDPMESSAGETYPE_UDPVOICECELTALPHA;
	public static final int CODEC_BETA = UDPMESSAGETYPE_UDPVOICECELTBETA;

	public static final int SAMPLE_RATE = 48000;
	public static final int FRAME_SIZE = SAMPLE_RATE / 100;

	public static final int UDP_BUFFER_SIZE = 2048;

	/**
	 * The time window during which the last successful UDP ping must have been
	 * transmitted. If the time since the last successful UDP ping is greater
	 * than this treshold the connection falls back on TCP tunneling.
	 *
	 * NOTE: This is the time when the last successfully received ping was SENT
	 * by the client.
	 *
	 * 6000 gives 1 second reply-time as the ping interval is 5000 seconds
	 * currently.
	 */
	public static final int UDP_PING_TRESHOLD = 6000;

	private static final MessageType[] MT_CONSTANTS = MessageType.class.getEnumConstants();

	private static final int protocolVersion = (1 << 16) | (2 << 8) |
											   (3 & 0xFF);

	private static final int supportedCodec = 0x8000000b;

	public Map<Integer, Channel> channels = new HashMap<Integer, Channel>();
	public Map<Integer, User> users = new HashMap<Integer, User>();
	public Channel currentChannel = null;
	public User currentUser = null;
	public boolean canSpeak = true;
	public int codec = CODEC_NOCODEC;
	private final MumbleConnectionHost connectionHost;
	private final AudioOutputHost audioHost;
	private final Context ctx;

	private DataInputStream in;
	private DataOutputStream out;
	private DatagramSocket udpOut;
	private long lastUdpPing;
	boolean usingUdp = false;

	private Thread pingThread;
	private boolean disconnecting = false;

	private final String host;
	private final int port;
	private final String username;
	private final String password;
	//TODO: Find better place, if there is a better place?
	private final long id; /* Server ID */

	private AudioOutput ao;
	private Thread audioOutputThread;
	private Thread udpReaderThread;
	private Thread tcpReaderThread;
	private final Object stateLock = new Object();
	private final CryptState cryptState = new CryptState();
	private String errorString;

	/**
	 * Constructor for new connection thread.
	 *
	 * This thread should be started shortly after construction. Construction
	 * sets the connection state for the host to "Connecting" even if the actual
	 * connection won't be attempted until the thread has been started.
	 *
	 * This is to combat an issue where the Service is asked to connect and the
	 * thread is started but the thread isn't given execution time before
	 * another activity checks for connection state and finds out the service is
	 * in Disconnected state.
	 *
	 * @param connectionHost_
	 *            Host interface for this Connection
	 * @param audioHost_
	 *            Host interface for underlying AudioOutputs.
	 * @param host_
	 *            Mumble server host address
	 * @param port_
	 *            Mumble server port
	 * @param username_
	 *            Username
	 * @param password_
	 *            Server password
	 * @param audioSettings_
	 *            Settings for the AudioOutput wrapped in a class so
	 *            MumbleConnection doesn't need to know what it has to pass on.
	 */
	public MumbleConnection(
		final MumbleConnectionHost connectionHost,
		final AudioOutputHost audioHost,
		final String host,
		final int port,
		final String username,
		final String password,
		final Context ctx,
		final long id) {
		this.connectionHost = connectionHost;
		this.audioHost = audioHost;
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		this.ctx = ctx;
		this.id = id;

		connectionHost.setConnectionState(ConnectionState.Connecting);
	}

	public final void disconnect() {
		disconnecting = true;
		synchronized (stateLock) {
			if (tcpReaderThread != null) {
				tcpReaderThread.interrupt();
			}
			if (udpReaderThread != null) {
				udpReaderThread.interrupt();
			}

			connectionHost.setConnectionState(ConnectionState.Disconnecting);
			stateLock.notifyAll();
		}
	}

	public String getError() {
		final String error = errorString;
		errorString = null;
		return error;
	}
	
	public long getServerID() {
		return this.id;
	}

	public final boolean isConnectionAlive() {
		return !disconnecting;
	}

	public final boolean isSameServer(
		final String host_,
		final int port_,
		final String username_,
		final String password_) {
		return host.equals(host_) && port == port_ &&
			   username.equals(username_) && password.equals(password_);
	}

	public final void joinChannel(final int channelId) {		
		final UserState.Builder us = UserState.newBuilder();
		us.setSession(currentUser.session);
		us.setChannelId(channelId);
		try {
			sendMessage(MessageType.UserState, us);
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}
	
	public final void authenticate(final String[] tokens) {
		final Authenticate.Builder auth = Authenticate.newBuilder();
		for (String string : tokens) {
			auth.addTokens(string);
		}
		try {
			this.sendMessage(MessageType.Authenticate, auth);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public final void run() {
		try {
			SSLSocket tcpSocket;
			DatagramSocket udpSocket;

			Log.i(Globals.LOG_TAG, String.format("Connecting to host \"%s\", port %s", host, port));
			final SSLContext ctx_ = SSLContext.getInstance("TLS");
			ctx_.init(
				null,
				new TrustManager[] { new LocalSSLTrustManager() },
				null);
			final SSLSocketFactory factory = ctx_.getSocketFactory();
			tcpSocket = (SSLSocket) factory.createSocket(host, port);
			tcpSocket.setUseClientMode(true);
			tcpSocket.setEnabledProtocols(new String[] { "TLSv1" });
			tcpSocket.startHandshake();

			Log.i(Globals.LOG_TAG, "TCP/SSL socket opened");

			udpSocket = new DatagramSocket();
			udpSocket.connect(Inet4Address.getByName(host), port);

			Log.i(Globals.LOG_TAG, "UDP Socket opened");

			synchronized (stateLock) {
				connectionHost.setConnectionState(ConnectionState.Synchronizing);
			}

			handleProtocol(tcpSocket, udpSocket);

			// Clean connection state that might have been initialized.
			// Do this before closing the socket as the threads could use it.
			if (ao != null) {
				ao.stop();
				audioOutputThread.join();
			}

			if (pingThread != null) {
				pingThread.interrupt();
			}

			// FIXME: These throw exceptions for some reason.
			// Even with the checks in place
			if (tcpSocket.isConnected()) {
				tcpSocket.close();
			}
			if (udpSocket.isConnected()) {
				udpSocket.close();
			}
		} catch (final NoSuchAlgorithmException e) {
			Log.e(Globals.LOG_TAG, String.format("Could not connect to Mumble server \"%s:%s\"", host, port), e);
		} catch (final KeyManagementException e) {
			Log.e(Globals.LOG_TAG, String.format("Could not connect to Mumble server \"%s:%s\"", host, port), e);
		} catch (final UnknownHostException e) {
			errorString = String.format("Host \"%s\" unknown", host);
			Log.e(Globals.LOG_TAG, errorString, e);
		} catch (final ConnectException e) {
			errorString = "The host refused connection";
			Log.e(Globals.LOG_TAG, errorString, e);
		} catch (final IOException e) {
			Log.e(Globals.LOG_TAG, String.format("Could not connect to Mumble server \"%s:%s\"", host, port), e);
		} catch (final InterruptedException e) {
			Log.e(Globals.LOG_TAG, String.format("Could not connect to Mumble server \"%s:%s\"", host, port), e);
		}

		synchronized (stateLock) {
			connectionHost.setConnectionState(ConnectionState.Disconnected);
		}
	}

	public final void sendChannelTextMessage(final String message, final Channel channel) {
		final TextMessage.Builder tmb = TextMessage.newBuilder();
		tmb.addChannelId(channel.id);
		tmb.setMessage(message);
		try {
			sendMessage(MessageType.TextMessage, tmb);
		} catch (final IOException e) {
			Log.e(Globals.LOG_TAG, "Could not send text message", e);
		}

		final Message msg = new Message();
		msg.timestamp = System.currentTimeMillis();
		msg.message = message;
		msg.channel = channel;
		msg.direction = Message.DIRECTION_SENT;
		connectionHost.messageSent(msg);
	}

	public final void sendMessage(
		final MessageType t,
		final MessageLite.Builder b) throws IOException {
		final MessageLite m = b.build();
		final short type = (short) t.ordinal();
		final int length = m.getSerializedSize();

		synchronized (out) {
			out.writeShort(type);
			out.writeInt(length);
			m.writeTo(out);
		}

		if (t != MessageType.Ping) {
			Log.d(Globals.LOG_TAG, "<<< " + t);
		}
	}

	public final void sendUdpMessage(
		final byte[] buffer,
		final int length,
		final boolean forceUdp) throws IOException {
		// FIXME: This would break things because we don't handle nonce resync messages
//		if (!cryptState.isInitialized()) {
//			return;
//		}

		if (forceUdp ||
			lastUdpPing + UDP_PING_TRESHOLD > System.currentTimeMillis()) {
			if (!usingUdp && !forceUdp) {
				Log.i(Globals.LOG_TAG, "MumbleConnection: UDP enabled");
				usingUdp = true;
			}

			final byte[] encryptedBuffer = cryptState.encrypt(buffer, length);
			final DatagramPacket outPacket = new DatagramPacket(
				encryptedBuffer,
				encryptedBuffer.length);

			outPacket.setAddress(Inet4Address.getByName(host));
			outPacket.setPort(port);

			udpOut.send(outPacket);
		} else {
			if (usingUdp) {
				Log.i(Globals.LOG_TAG, "MumbleConnection: UDP disabled");
				usingUdp = false;
			}

			final short type = (short) MessageType.UDPTunnel.ordinal();

			synchronized (out) {
				out.writeShort(type);
				out.writeInt(length);
				out.write(buffer, 0, length);
			}
		}
	}

	private Channel findChannel(final int id) {
		return channels.get(id);
	}

	private User findUser(final int session_) {
		return users.get(session_);
	}

	private void handleProtocol(
		final Socket tcpSocket,
		final DatagramSocket udpSocket) throws IOException,
		InterruptedException {
		synchronized (stateLock) {
			if (disconnecting) {
				return;
			}
		}

		out = new DataOutputStream(tcpSocket.getOutputStream());
		in = new DataInputStream(tcpSocket.getInputStream());
		udpOut = udpSocket;

		final Version.Builder v = Version.newBuilder();
		v.setVersion(protocolVersion);
		v.setRelease("MumbleAndroid 0.0.1-dev");

		final Authenticate.Builder a = Authenticate.newBuilder();
		a.setUsername(username);
		a.setPassword(password);
		a.addCeltVersions(supportedCodec);

		sendMessage(MessageType.Version, v);
		sendMessage(MessageType.Authenticate, a);

		synchronized (stateLock) {
			if (disconnecting) {
				return;
			}
		}

		// Process the stream in separate thread so we can interrupt it if necessary
		// without interrupting the whole connection thread and thus allowing us to
		// disconnect cleanly.
		final MumbleSocketReader tcpReader = new MumbleSocketReader(stateLock) {
			private byte[] msg = null;

			@Override
			public boolean isRunning() {
				return tcpSocket.isConnected() && !disconnecting &&
					   super.isRunning();
			}

			@Override
			protected void process() throws IOException {
				final short type = in.readShort();
				final int length = in.readInt();
				if (msg == null || msg.length != length) {
					msg = new byte[length];
				}
				in.readFully(msg);

				// Serialize the message processing by performing it inside
				// the stateLock.
				synchronized (stateLock) {
					processMsg(MT_CONSTANTS[type], msg);
				}
			}
		};

		final MumbleSocketReader udpReader = new MumbleSocketReader(stateLock) {
			private final DatagramPacket packet = new DatagramPacket(
				new byte[UDP_BUFFER_SIZE],
				UDP_BUFFER_SIZE);

			@Override
			public boolean isRunning() {
				return udpSocket.isConnected() && !disconnecting;
			}
			
			@Override
			protected void process() throws IOException {
				udpSocket.receive(packet);

				final byte[] buffer = cryptState.decrypt(
					packet.getData(),
					packet.getLength());

				// Decrypt might return null if the buffer was total garbage.
				if (buffer == null) {
					return;
				}

				// Serialize the message processing by performing it inside
				// the stateLock.
				synchronized (stateLock) {
//					Log.i(Globals.LOG_TAG, "MumbleConnection: Received UDP");
					processUdpPacket(buffer, buffer.length);
				}
			}
		};

		tcpReaderThread = new Thread(tcpReader, "TCP Reader");
		udpReaderThread = new Thread(udpReader, "UDP Reader");

		tcpReaderThread.start();
		udpReaderThread.start();

		synchronized (stateLock) {
			while (tcpReaderThread.isAlive() && tcpReader.isRunning() &&
				   udpReaderThread.isAlive() && udpReader.isRunning()) {
				stateLock.wait();
			}

			// connectionHost.setConnectionState(ConnectionState.Disconnecting);

			// Interrupt both threads in case only one of them was closed.
			tcpReaderThread.interrupt();
			udpReaderThread.interrupt();

			tcpReaderThread = null;
			udpReaderThread = null;
		}
	}

	private void handleTextMessage(final TextMessage ts) {
		User u = null;
		if (ts.hasActor()) {
			u = findUser(ts.getActor());
		}

		final Message msg = new Message();
		msg.timestamp = System.currentTimeMillis();
		msg.message = ts.getMessage();
		msg.actor = u;
		msg.direction = Message.DIRECTION_RECEIVED;
		msg.channelIds = ts.getChannelIdCount();
		msg.treeIds = ts.getTreeIdCount();
		connectionHost.messageReceived(msg);
	}

	private void processMsg(final MessageType t, final byte[] buffer)
		throws IOException {
		Channel channel;
		User user;

		switch (t) {
		case UDPTunnel:
			processUdpPacket(buffer, buffer.length);
			break;
		case Ping:
			// ignore
			break;
		case CodecVersion:
			final boolean oldCanSpeak = canSpeak;
			final CodecVersion codecVersion = CodecVersion.parseFrom(buffer);
			codec = CODEC_NOCODEC;
			if (codecVersion.hasAlpha() &&
				codecVersion.getAlpha() == supportedCodec) {
				codec = CODEC_ALPHA;
			} else if (codecVersion.hasBeta() &&
					   codecVersion.getBeta() == supportedCodec) {
				codec = CODEC_BETA;
			}
			canSpeak = canSpeak && (codec != CODEC_NOCODEC);

			if (canSpeak != oldCanSpeak) {
				connectionHost.currentUserUpdated();
			}

			break;
		case Reject:
			final Reject reject = Reject.parseFrom(buffer);
			errorString = String.format(
				"Connection rejected: %s",
				reject.getReason());
			Log.e(Globals.LOG_TAG, String.format("Received Reject message: %s", reject.getReason()));
			break;
		case ServerSync:
			final ServerSync ss = ServerSync.parseFrom(buffer);

			// We do some things that depend on being executed only once here
			// so for now assert that there won't be multiple ServerSyncs.
			Assert.assertNull("A second ServerSync received.", currentUser);

			currentUser = findUser(ss.getSession());
			currentUser.isCurrent = true;
			currentChannel = currentUser.getChannel();

			pingThread = new Thread(new PingThread(this), "Ping");
			pingThread.start();
			Log.d(Globals.LOG_TAG, ">>> " + t);

			ao = new AudioOutput(ctx, audioHost);
			audioOutputThread = new Thread(ao, "audio output");
			audioOutputThread.start();

			final UserState.Builder usb = UserState.newBuilder();
			usb.setSession(currentUser.session);
			sendMessage(MessageType.UserState, usb);

			connectionHost.setConnectionState(ConnectionState.Connected);

			connectionHost.currentChannelChanged();
			connectionHost.currentUserUpdated();
			break;
		case ChannelState:
			final ChannelState cs = ChannelState.parseFrom(buffer);
			channel = findChannel(cs.getChannelId());
			if (channel != null) {
				if (cs.hasName()) {
					channel.name = cs.getName();
				}
				connectionHost.channelUpdated(channel);
				break;
			}

			// New channel
			channel = new Channel();
			channel.id = cs.getChannelId();
			channel.name = cs.getName();
			channels.put(channel.id, channel);
			connectionHost.channelAdded(channel);
			break;
		case ChannelRemove:
			final ChannelRemove cr = ChannelRemove.parseFrom(buffer);
			channel = findChannel(cr.getChannelId());
			channel.removed = true;
			channels.remove(channel.id);
			connectionHost.channelRemoved(channel.id);
			break;
		case UserState:
			final UserState us = UserState.parseFrom(buffer);
			user = findUser(us.getSession());

			boolean added = false;
			boolean currentUserUpdated = false;
			boolean channelUpdated = false;

			if (user == null) {
				user = new User();
				user.session = us.getSession();
				users.put(user.session, user);
				added = true;
			}

			// Test if user has self muted
			if (us.hasSelfDeaf() || us.hasSelfMute()) {
				if (us.getSelfDeaf()) {
					user.selfState = TalkingState.DEAFENED;
				} else if (us.getSelfMute()) {
					user.selfState = TalkingState.MUTED;
				} else {
					user.selfState = TalkingState.PASSIVE;
				}
			}
			
			// Supervisory states!
			if (us.hasMute()) {
				user.muted = us.getMute();
			}

			if (us.hasDeaf()) {
				user.deafened = us.getDeaf();
				user.muted |= user.deafened;
			}

			if (us.hasName()) {
				user.name = us.getName();
			}

			if (added || us.hasChannelId()) {
				user.setChannel(channels.get(us.getChannelId()));
				channelUpdated = true;
			}

			// If this is the current user, do extra updates on local state.
			if (currentUser != null && us.getSession() == currentUser.session) {
				if (us.hasMute() || us.hasSuppress()) {
					// TODO: Check the logic
					// Currently Mute+Suppress true -> Either of them false results
					// in canSpeak = true
					if (us.hasMute()) {
						canSpeak = (codec != CODEC_NOCODEC) && !us.getMute();
					}
					if (us.hasSuppress()) {
						canSpeak = (codec != CODEC_NOCODEC) &&
								   !us.getSuppress();
					}
				}

				currentUserUpdated = true;
			}

			if (channelUpdated) {
				connectionHost.channelUpdated(user.getChannel());
			}

			if (added) {
				connectionHost.userAdded(user);
			} else {
				connectionHost.userUpdated(user);
			}

			if (currentUserUpdated) {
				connectionHost.currentUserUpdated();
			}
			if (currentUserUpdated && channelUpdated) {
				currentChannel = user.getChannel();
				connectionHost.currentChannelChanged();
			}
			break;
		case UserRemove:
			final UserRemove ur = UserRemove.parseFrom(buffer);
			user = findUser(ur.getSession());
			users.remove(user.session);

			// Remove the user from the channel as well.
			user.getChannel().userCount--;

			connectionHost.channelUpdated(user.getChannel());
			connectionHost.userRemoved(user.session);
			break;
		case TextMessage:
			handleTextMessage(TextMessage.parseFrom(buffer));
			break;
		case CryptSetup:
			final CryptSetup cryptsetup = CryptSetup.parseFrom(buffer);

			Log.d(Globals.LOG_TAG, "MumbleConnection: CryptSetup");

			if (cryptsetup.hasKey() && cryptsetup.hasClientNonce() &&
				cryptsetup.hasServerNonce()) {

				// Full key setup
				cryptState.setKeys(
					cryptsetup.getKey().toByteArray(),
					cryptsetup.getClientNonce().toByteArray(),
					cryptsetup.getServerNonce().toByteArray());
			} else if (cryptsetup.hasServerNonce()) {
				// Server syncing its nonce to us.
				Log.d(Globals.LOG_TAG, "MumbleConnection: Server sending nonce");
				cryptState.setServerNonce(cryptsetup.getServerNonce().toByteArray());
			} else {
				// Server wants our nonce.
				Log.d(
					Globals.LOG_TAG,
					"MumbleConnection: Server requesting nonce");
				final CryptSetup.Builder nonceBuilder = CryptSetup.newBuilder();
				nonceBuilder.setClientNonce(cryptState.getClientNonce());
				sendMessage(MessageType.CryptSetup, nonceBuilder);
			}
			break;
		default:
			Log.w(Globals.LOG_TAG, "unhandled message type " + t);
		}
	}

	private void processUdpPacket(final byte[] buffer, final int length) {
		final int type = buffer[0] >> 5 & 0x7;
		if (type == UDPMESSAGETYPE_UDPPING) {
			final long timestamp = ((long) (buffer[1] & 0xFF) << 56) |
								   ((long) (buffer[2] & 0xFF) << 48) |
								   ((long) (buffer[3] & 0xFF) << 40) |
								   ((long) (buffer[4] & 0xFF) << 32) |
								   ((long) (buffer[5] & 0xFF) << 24) |
								   ((long) (buffer[6] & 0xFF) << 16) |
								   ((long) (buffer[7] & 0xFF) << 8) |
								   ((buffer[8] & 0xFF));

			if (lastUdpPing < timestamp) {
				lastUdpPing = timestamp;
			}
		} else {
			processVoicePacket(buffer);
		}
	}

	private void processVoicePacket(final byte[] buffer) {
		final int type = buffer[0] >> 5 & 0x7;
		final int flags = buffer[0] & 0x1f;

		// There is no speex support...
		if (type != UDPMESSAGETYPE_UDPVOICECELTALPHA &&
			type != UDPMESSAGETYPE_UDPVOICECELTBETA) {
			return;
		}

		// Don't try to decode the unsupported codec version.
		if (type != codec) {
			return;
		}

		final PacketDataStream pds = new PacketDataStream(buffer);
		// skip type / flags
		pds.skip(1);
		final long uiSession = pds.readLong();

		final User u = findUser((int) uiSession);
		if (u == null) {
			Log.e(Globals.LOG_TAG, "User session " + uiSession + " not found!");
		}

		// Rewind the packet. Otherwise consumers are confusing to implement.
		pds.rewind();
		ao.addFrameToBuffer(u, pds, flags);
	}
}
