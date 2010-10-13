package org.pcgod.mumbleclient.app;

import org.pcgod.mumbleclient.R;

import android.app.Activity;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

public class ServerInfo extends Activity {
	private final OnClickListener addButtonListener = new OnClickListener() {
		public void onClick(final View v) {
			final EditText nameEdit = (EditText) findViewById(R.id.serverNameEdit);
			final EditText hostEdit = (EditText) findViewById(R.id.serverHostEdit);
			final EditText portEdit = (EditText) findViewById(R.id.serverPortEdit);
			final EditText usernameEdit = (EditText) findViewById(R.id.serverUsernameEdit);
			final EditText passwordEdit = (EditText) findViewById(R.id.serverPasswordEdit);

			final String name = (nameEdit).getText().toString();
			final String host = (hostEdit).getText().toString();

			int port;
			try {
				port = Integer.parseInt((portEdit).getText().toString());
			} catch (final NumberFormatException ex) {
				port = 64738;
			}

			final String username = (usernameEdit).getText().toString();
			final String password = (passwordEdit).getText().toString();

			final DbAdapter db = new DbAdapter(v.getContext());

			db.open();
			final long serverId = ServerInfo.this.getIntent().getLongExtra(
				"serverId",
				-1);
			if (serverId != -1) {
				db.updateServer(serverId, name, host, port, username, password);
			} else {
				db.createServer(name, host, port, username, password);
			}
			db.close();

			finish();
		}
	};

	@Override
	protected final void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.server_add);
		
		final Button addButton = (Button) findViewById(R.id.serverAdd);
		addButton.setOnClickListener(addButtonListener);

		final long serverId = this.getIntent().getLongExtra("serverId", -1);
		if (serverId != -1) {
			final EditText nameEdit = (EditText) findViewById(R.id.serverNameEdit);
			final EditText hostEdit = (EditText) findViewById(R.id.serverHostEdit);
			final EditText portEdit = (EditText) findViewById(R.id.serverPortEdit);
			final EditText usernameEdit = (EditText) findViewById(R.id.serverUsernameEdit);
			final EditText passwordEdit = (EditText) findViewById(R.id.serverPasswordEdit);

			final DbAdapter db = new DbAdapter(this);
			db.open();
			final Cursor c = db.fetchServer(serverId);
			nameEdit.setText(c.getString(c.getColumnIndexOrThrow(DbAdapter.SERVER_COL_NAME)));
			hostEdit.setText(c.getString(c.getColumnIndexOrThrow(DbAdapter.SERVER_COL_HOST)));
			portEdit.setText(Integer.toString(c.getInt(c.getColumnIndexOrThrow(DbAdapter.SERVER_COL_PORT))));
			usernameEdit.setText(c.getString(c.getColumnIndexOrThrow(DbAdapter.SERVER_COL_USERNAME)));
			passwordEdit.setText(c.getString(c.getColumnIndexOrThrow(DbAdapter.SERVER_COL_PASSWORD)));
			addButton.setText(R.string.serverChange);
			c.close();
			db.close();
		}
	}
}
