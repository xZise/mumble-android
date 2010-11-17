package org.pcgod.mumbleclient.app;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import junit.framework.Assert;

import org.pcgod.mumbleclient.Globals;
import org.pcgod.mumbleclient.R;
import org.pcgod.mumbleclient.service.MumbleService;
import org.pcgod.mumbleclient.service.model.Channel;
import org.pcgod.mumbleclient.service.model.User;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.DialogInterface.OnCancelListener;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

/**
 * The main connection view.
 *
 * The state of this activity depends closely on the state of the underlying
 * MumbleService. When the activity is started it can't really do anything else
 * than initialize its member variables until it has acquired a reference to the
 * MumbleService.
 *
 * Once the MumbleService reference has been acquired the activity is in one of
 * the three states:
 * <dl>
 * <dt>Connecting to server
 * <dd>MumbleService has just been started and ChannelList should wait until the
 * connection has been established. In this case the ChannelList should be very
 * careful as it doesn't have a visible channel and the Service doesn't have a
 * current channel.
 *
 * <dt>Connected to server
 * <dd>When the Activity is resumed during an established Mumble connection it
 * has connection immediately available and is free to act freely.
 *
 * <dt>Disconnecting or Disconnected
 * <dd>If the ChannelList is resumed after the Service has been disconnected the
 * List should exit immediately.
 * </dl>
 *
 * NOTE: Service enters 'Connected' state when it has received and processed
 * server sync message. This means that at this point the service should be
 * fully initialized.
 *
 * And just so the state wouldn't be too easy the connection can be cancelled.
 * Disconnecting the service is practically synchronous operation. Intents
 * broadcast by the Service aren't though. This means that after the ChannelList
 * disconnects the service it might still have some unprocessed intents queued
 * in a queue. For this reason all intents that require active connection must
 * take care to check that the connection is still alive.
 *
 * @author pcgod, Rantanen
 *
 */
public class ChannelList extends ConnectedActivity {
	/**
	 * Handles broadcasts from MumbleService
	 */
	private class ChannelBroadcastReceiver extends BroadcastReceiver {
		@Override
		public final void onReceive(final Context ctx, final Intent i) {
			final String action = i.getAction();

			// It might be possible that intents are being received from the
			// service before the service has been acquired by the Activity.
			// Since processing intents requires the service we'll ignore the
			// intents until we have the service reference.
			//
			// WARNING: There might be issues with this check. At first the
			// broadcast receiver was registered in onServiceBound method but
			// it was later changed to onResume for some reason.
			//
			// TODO: Figure out the correct solution.
			if (mService == null) {
				return;
			}

			// First process intents that do NOT require active connection.
			if (action.equals(MumbleService.INTENT_CONNECTION_STATE_CHANGED)) {
				onConnectionStateChanged();
				return;
			}

			// Next try processing intents that imply an active connection.

			// If the connection is NOT active at this point, skip everything.
			// This means the connection WAS active but it was disconnected
			// before the intents were processed.
			if (!mService.isConnected()) {
				return;
			}

			if (action.equals(MumbleService.INTENT_CURRENT_CHANNEL_CHANGED)) {
				setChannel(mService.getCurrentChannel());
				return;
			}

			if (action.equals(MumbleService.INTENT_CHANNEL_LIST_UPDATE)) {
				// Channel list update doesn't matter if the visible channel
				// isn't valid from the beginning.
				if (visibleChannel == null) {
					return;
				}

				boolean visibleChannelValid = false;
				for (final Channel c : mService.getChannelList()) {
					if (visibleChannel.id == c.id) {
						visibleChannelValid = true;
						break;
					}
				}

				if (!visibleChannelValid) {
					setChannel(mService.getCurrentChannel());
				}
				return;
			}

			if (action.equals(MumbleService.INTENT_USER_UPDATE) ||
				action.equals(MumbleService.INTENT_USER_ADDED)) {

				final User updatedUser = (User) i.getSerializableExtra(MumbleService.EXTRA_USER);
				usersAdapter.refreshUser(updatedUser);

				return;
			}

			if (action.equals(MumbleService.INTENT_USER_REMOVED)) {
				final User removedUser = (User) i.getSerializableExtra(MumbleService.EXTRA_USER);
				usersAdapter.removeUser(removedUser.session);
				return;
			}

			if (action.equals(MumbleService.INTENT_CURRENT_USER_UPDATED)) {
				// If the current user was updated, synchronize controls as well
				// as it might have been muted/unmuted for example.
				synchronizeControls();

				return;
			}

			Assert.fail("Unknown intent broadcast");
		}

		private final void onConnectionStateChanged() {
			switch (mService.getConnectionState()) {
			case Connecting:
				Log.i(Globals.LOG_TAG, "ChannelList: Connecting");
				onConnecting();
				break;
			case Synchronizing:
				Log.i(Globals.LOG_TAG, "ChannelList: Synchronizing");
				onSynchronizing();
				break;
			case Connected:
				Log.i(Globals.LOG_TAG, "ChannelList: Connected");

				// The service might have been fast enough to properly connect
				// before the broadcast was resolved. Try calling onConnected
				// just in case.
				onConnected();
				break;
			case Disconnected:
			case Disconnecting:
				Log.i(Globals.LOG_TAG, "ChannelList: Disconnected");
				onDisconnected();
				break;
			default:
				Assert.fail("Unknown connection state");
			}
		}
	}

	private final Runnable usersChangedCallback = new Runnable() {

		@Override
		public void run() {
			final boolean hasUsers = usersAdapter.getCount() > 0;
			noUsersText.setVisibility(hasUsers ? View.GONE : View.VISIBLE);
			channelUsersList.setVisibility(hasUsers ? View.VISIBLE : View.GONE);
		}
	};

	public static final String JOIN_CHANNEL = "join_channel";
	public static final String SAVED_STATE_VISIBLE_CHANNEL = "visible_channel";

	private static final int MENU_CHAT = Menu.FIRST;
	private static final int MENU_ACCESS_TOKENS = Menu.FIRST + 1;

	Channel visibleChannel;

	private TextView channelNameText;
	private Button browseButton;
	private ListView channelUsersList;
	private UserListAdapter usersAdapter;
	private TextView noUsersText;
	private ToggleButton speakButton;
	private Button joinButton;
	private CheckBox speakerCheckBox;

	private ChannelBroadcastReceiver bcReceiver;
	private AlertDialog mChannelSelectDialog;
	List<Channel> selectableChannels;
	private ProgressDialog mProgressDialog;
	private AlertDialog mDisconnectDialog;

	public final OnClickListener browseButtonClickEvent = new OnClickListener() {
		@Override
		public void onClick(final View v) {
			// Save the current channels so we can match them by index even if
			// the real
			// channels change.
			final List<Channel> currentChannels = mService.getChannelList();
			selectableChannels = new ArrayList<Channel>(currentChannels);

			final Channel currentChannel = mService.getCurrentChannel();
			int currentChannelId = -1;
			if (currentChannel != null) {
				currentChannelId = currentChannel.id;
			}

			final Iterator<Channel> i = selectableChannels.iterator();
			int step = 0;
			final String[] channelNames = new String[selectableChannels.size()];
			while (i.hasNext()) {
				final Channel c = i.next();
				if (c.id == currentChannelId) {
					channelNames[step] = String.format(
						"%s (C, %d)",
						c.name,
						c.userCount);
				} else {
					channelNames[step] = String.format(
						"%s (%d)",
						c.name,
						c.userCount);
				}
				step++;
			}

			new AlertDialog.Builder(ChannelList.this).setCancelable(true).setItems(
				channelNames,
				channelListClickEvent).show();
		}
	};

	public final DialogInterface.OnClickListener channelListClickEvent = new DialogInterface.OnClickListener() {
		@Override
		public void onClick(final DialogInterface dialog, final int which) {
			setChannel(selectableChannels.get(which));
		}
	};

	private final OnClickListener joinButtonClickEvent = new OnClickListener() {
		public void onClick(final View v) {
			mService.joinChannel(visibleChannel.id);
		}
	};

	private final OnClickListener speakButtonClickEvent = new OnClickListener() {
		public void onClick(final View v) {
			mService.setRecording(!mService.isRecording());
		}
	};

	public final DialogInterface.OnClickListener onDisconnectConfirm = new DialogInterface.OnClickListener() {
		@Override
		public void onClick(final DialogInterface dialog, final int which) {
			mService.disconnect();
		}
	};

	@Override
	public final boolean onCreateOptionsMenu(final Menu menu) {
		menu.add(0, MENU_CHAT, 0, "Chat").setIcon(
			android.R.drawable.ic_btn_speak_now);
		menu.add(0, MENU_ACCESS_TOKENS, 0, "Access Tokens").setIcon(
			android.R.drawable.ic_lock_lock);
		return true;
	}

	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
			final AlertDialog.Builder b = new AlertDialog.Builder(this);
			b.setIcon(android.R.drawable.ic_dialog_alert);
			b.setTitle("Disconnect");
			b.setMessage("Are you sure you want to disconnect from Mumble?");
			b.setPositiveButton(R.string.yes, onDisconnectConfirm);
			b.setNegativeButton(R.string.no, null);
			mDisconnectDialog = b.show();

			return true;
		}

		return super.onKeyDown(keyCode, event);
	}

	@Override
	public final boolean onMenuItemSelected(
		final int featureId,
		final MenuItem item) {
		switch (item.getItemId()) {
		case MENU_CHAT:
			final Intent i = new Intent(this, ChatActivity.class);
			startActivity(i);
			return true;
		case MENU_ACCESS_TOKENS :
			final Intent tokensIntent = new Intent(this, AccessTokens.class);
			startActivity(tokensIntent);
			return true;
		default:
			return super.onMenuItemSelected(featureId, item);
		}
	}

	private void cleanDialogs() {
		if (mChannelSelectDialog != null) {
			mChannelSelectDialog.dismiss();
			mChannelSelectDialog = null;
		}

		if (mProgressDialog != null) {
			mProgressDialog.dismiss();
			mProgressDialog = null;
		}

		if (mDisconnectDialog != null) {
			mDisconnectDialog.dismiss();
			mDisconnectDialog = null;
		}
	}

	/**
	 * Handles activity initialization when the Service has connected.
	 *
	 * Should be called when there is a reason to believe that the connection
	 * might have became valid. The connection MUST be established but other
	 * validity criteria may still be unfilled such as server synchronization
	 * being complete.
	 *
	 * The method implements the logic required for making sure that the
	 * Connected service is in such a state that it fills all the connection
	 * criteria for ChannelList.
	 *
	 * The method also takes care of making sure that its initialization code
	 * is executed only once so calling it several times doesn't cause problems.
	 */
	private void onConnected() {
		// We are now connected! \o/
		if (mProgressDialog != null) {
			mProgressDialog.dismiss();
			mProgressDialog = null;
		}
		
		// Tell the access tokens to the server
		DbAdapter db = new DbAdapter(this);
		db.open();
		try {
			AccessToken[] tokens = db.fetchAccessTokenByServerId(this.mService.getServerID());
			String[] strTokens = new String[tokens.length];
			for (int i = 0; i < strTokens.length; i++) {
				strTokens[i] = tokens[i].value;
			}
			this.mService.authenticate(strTokens);
		} finally {
			db.close();
		}

		// If we don't have visible channel selected, default to the current channel.
		// Setting channel also synchronizes the UI so we don't need to do it manually.
		//
		// TODO: Resync channel if current channel was visible on pause.
		// Currently if the user is moved to another channel while this activity
		// is paused the channel isn't updated when the activity resumes.
		if (visibleChannel == null) {
			setChannel(mService.getCurrentChannel());
		} else {
			synchronizeControls();
			usersAdapter.notifyDataSetChanged();
		}

		usersAdapter.setUsers(mService.getUserList());
	}

	/**
	 * Handles activity initialization when the Service is connecting.
	 */
	private void onConnecting() {
		showProgressDialog(R.string.connectionProgressSynchronizingMessage);
		synchronizeControls();
	}

	private void onDisconnected() {
		cleanDialogs();
		// TODO: this doesn't work for unknown host errors
		final String error = mService.getError();
		if (error != null) {
			Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
		}
		finish();
	}

	private void onSynchronizing() {
		showProgressDialog(R.string.connectionProgressSynchronizingMessage);
		synchronizeControls();
	}

	private void setChannel(final Channel channel) {
		visibleChannel = channel;

		final int channelId = channel.id;
		usersAdapter.setVisibleChannel(channelId);
		synchronizeControls();
	}

	private void showProgressDialog(final int message) {
		if (mProgressDialog == null) {
			mProgressDialog = ProgressDialog.show(
				ChannelList.this,
				getString(R.string.connectionProgressTitle),
				getString(message),
				true,
				true,
				new OnCancelListener() {
					@Override
					public void onCancel(final DialogInterface dialog) {
						mService.disconnect();
						mProgressDialog.setMessage(getString(R.string.connectionProgressDisconnectingMessage));
					}
				});
		} else {
			mProgressDialog.setMessage(getString(message));
		}
	}

	private void synchronizeControls() {
		if (mService == null || !mService.isConnected()) {
			findViewById(R.id.connectionViewRoot).setVisibility(View.GONE);
			speakButton.setEnabled(false);
			joinButton.setEnabled(false);
		} else {
			findViewById(R.id.connectionViewRoot).setVisibility(View.VISIBLE);
			if (mService.getCurrentChannel().id == visibleChannel.id) {
				speakButton.setVisibility(View.VISIBLE);
				speakButton.setEnabled(mService.canSpeak());
				speakButton.setChecked(mService.isRecording());
				joinButton.setVisibility(View.GONE);
			} else {
				speakButton.setVisibility(View.GONE);
				joinButton.setVisibility(View.VISIBLE);
				joinButton.setEnabled(true);
			}
			channelNameText.setText(visibleChannel.name);
		}
	}

	@Override
	protected final void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.channel_list);
		setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

		// Get the UI views
		channelNameText = (TextView) findViewById(R.id.channelName);
		browseButton = (Button) findViewById(R.id.browseButton);
		channelUsersList = (ListView) findViewById(R.id.channelUsers);
		noUsersText = (TextView) findViewById(R.id.noUsersText);
		speakButton = (ToggleButton) findViewById(R.id.speakButton);
		joinButton = (Button) findViewById(R.id.joinButton);
		speakerCheckBox = (CheckBox) findViewById(R.id.speakerCheckBox);

		// Set event handlers.
		browseButton.setOnClickListener(browseButtonClickEvent);
		joinButton.setOnClickListener(joinButtonClickEvent);
		speakButton.setOnClickListener(speakButtonClickEvent);

		usersAdapter = new UserListAdapter(
			this,
			channelUsersList,
			usersChangedCallback);
		channelUsersList.setAdapter(usersAdapter);

		// Disable speaker check box for now since switching between audio
		// inputs isn't supported.
		speakerCheckBox.setEnabled(false);
		speakerCheckBox.setVisibility(View.GONE);

		if (savedInstanceState != null) {
			final Channel channel = (Channel) savedInstanceState.getSerializable(SAVED_STATE_VISIBLE_CHANNEL);

			// Channel might be null if we for example caused screen rotation
			// while still connecting.
			if (channel != null) {
				setChannel(channel);
			}
		}
	}

	@Override
	protected final void onPause() {
		super.onPause();

		if (bcReceiver != null) {
			unregisterReceiver(bcReceiver);
			bcReceiver = null;
		}

		cleanDialogs();
	}

	@Override
	protected final void onResume() {
		super.onResume();

		final IntentFilter ifilter = new IntentFilter();
		ifilter.addAction(MumbleService.INTENT_CHANNEL_LIST_UPDATE);
		ifilter.addAction(MumbleService.INTENT_USER_UPDATE);
		ifilter.addAction(MumbleService.INTENT_USER_ADDED);
		ifilter.addAction(MumbleService.INTENT_USER_REMOVED);
		ifilter.addAction(MumbleService.INTENT_CONNECTION_STATE_CHANGED);
		ifilter.addAction(MumbleService.INTENT_CURRENT_CHANNEL_CHANGED);
		ifilter.addAction(MumbleService.INTENT_CURRENT_USER_UPDATED);
		bcReceiver = new ChannelBroadcastReceiver();
		registerReceiver(bcReceiver, ifilter);

		// Do not synchronize controls here as the service might not be
		// available. The onServiceBound event should arrive soon. Once it
		// arrives we'll first check that we are still connected to the server.
	}

	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putSerializable(SAVED_STATE_VISIBLE_CHANNEL, visibleChannel);
	}

	/**
	 * Signals that the service has been bound and is available for use.
	 */
	@Override
	protected final void onServiceBound() {
		switch (mService.getConnectionState()) {
		case Connecting:
			onConnecting();
			break;
		case Synchronizing:
			onSynchronizing();
			break;
		case Connected:
			onConnected();
			break;
		case Disconnected:
		case Disconnecting:
			onDisconnected();
			break;
		default:
			Assert.fail("Unknown connection state");
		}
	}
}
