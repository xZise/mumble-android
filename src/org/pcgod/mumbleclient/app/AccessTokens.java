package org.pcgod.mumbleclient.app;

import org.pcgod.mumbleclient.R;
import org.pcgod.mumbleclient.app.DbAdapter;

import android.os.Bundle;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

public class AccessTokens extends ConnectedActivity {

	private ArrayAdapter<AccessToken> adapter;
	private long serverID;
	private DbAdapter dbAdapter;
	private Button addToken;
	private TextView tokenName;
	
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
	                                ContextMenuInfo menuInfo) {
	  super.onCreateContextMenu(menu, v, menuInfo);
	  MenuInflater inflater = getMenuInflater();
	  inflater.inflate(R.menu.access_token_menu, menu);
	}
	
	@Override
	public boolean onContextItemSelected(MenuItem item) {
	  AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
	  switch (item.getItemId()) {
	  case R.id.accessTokenDelete :
//		  System.out.println(info);
		  AccessToken deleted = this.adapter.getItem(info.position);
		  this.dbAdapter.deleteAccessToken(deleted.id);
		  this.adapter.remove(deleted);
		  return true;
	  default:
		  return super.onContextItemSelected(item);
	  }
	}
	
	private void addToken() {
		String newToken = this.tokenName.getText().toString().trim();
		if (newToken.length() > 0) {
			this.adapter.add(this.dbAdapter.createAccessToken(this.serverID, this.tokenName.getText().toString()));
		} else {
			Toast.makeText(this, R.string.accessNothingToAdd, Toast.LENGTH_SHORT).show();
		}
	}
	
	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
			// If there are changes tell server the tokens
			
			// at the moment there are always changes *hust*
			String[] tokens = new String[this.adapter.getCount()];
			for (int i = 0; i < tokens.length; i++) {
				tokens[i] = this.adapter.getItem(i).value;
			}
			this.mService.authenticate(tokens);

		}

		return super.onKeyDown(keyCode, event);
	}
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.access_token_list);
        
        this.adapter = new ArrayAdapter<AccessToken>(this, android.R.layout.simple_list_item_1);
		
		this.addToken = (Button) findViewById(R.id.accessAddToken);
		this.tokenName = (EditText) findViewById(R.id.accessTokenName);
    }
    
    @Override
	protected void onServiceBound() {
		super.onServiceBound();
		ListView tokens = (ListView) findViewById(R.id.accessTokenList);
		tokens.setAdapter(this.adapter);
		this.registerForContextMenu(tokens);
		
		this.serverID = this.mService.getServerID();
		/* Access Tokens of an existing server or global access tokens */
		if (this.serverID >= -1) {
			this.dbAdapter = new DbAdapter(this);
			this.dbAdapter.open();
			// load all access tokens of the serverID
			for (AccessToken token : this.dbAdapter.fetchAccessTokenByServerId(serverID)) {
				this.adapter.add(token);
			}
			
			this.tokenName.setEnabled(true);
			this.addToken.setEnabled(true);
			
			this.addToken.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					AccessTokens.this.addToken();
				}
			});
			
		} else
		/* Invalid ID o.o */
		{
			
		}
    }
}
