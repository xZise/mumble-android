package org.pcgod.mumbleclient.app;

import org.pcgod.mumbleclient.R;
import org.pcgod.mumbleclient.app.DbAdapter;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

public class AccessTokens extends ConnectedListActivity {

	private static final int MENU_ADD_TOKEN = Menu.FIRST;
	
	private ArrayAdapter<AccessToken> adapter;
	private long serverID;
	private DbAdapter dbAdapter;
	
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.access_token_menu, menu);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
				.getMenuInfo();
		switch (item.getItemId()) {
		case R.id.accessTokenDelete:
			AccessToken deleted = this.adapter.getItem(info.position);
			this.dbAdapter.open();
			this.dbAdapter.deleteAccessToken(deleted.id);
			this.dbAdapter.close();
			this.adapter.remove(deleted);
			return true;
		case R.id.accessTokenEdit:
			this.renameToken(this.adapter.getItem(info.position));
			// Show dialog to change name
			return true;
		default:
			return super.onContextItemSelected(item);
		}
	}
	
	@Override
	public final boolean onCreateOptionsMenu(final Menu menu) {
		super.onCreateOptionsMenu(menu);
		menu.add(0, MENU_ADD_TOKEN, 0, R.string.accessAddToken).setIcon(
			android.R.drawable.ic_menu_add);
		return true;
	}
	
	private void addToken(String newToken) {
		newToken = newToken.trim(); // Adopted from the original source code
		if (newToken.length() > 0) {
			this.adapter.add(this.dbAdapter.createAccessToken(this.serverID, newToken));
		} else {
			Toast.makeText(this, R.string.accessNothingToAdd, Toast.LENGTH_SHORT).show();
		}
	}
	
	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
			// If there are changes tell server all the tokens
			
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
        
        ListView tokens = this.getListView();
        tokens.setAdapter(this.adapter);
		this.registerForContextMenu(tokens);

		this.dbAdapter = new DbAdapter(this);
		this.dbAdapter.open();
    }
    
    private void renameToken(final AccessToken token, String newName) {
    	newName = newName.trim();
    	if (newName.equals(token.value)) {
    		Toast.makeText(this, R.string.accessNoChanges, Toast.LENGTH_SHORT).show();
    	} else if (newName.length() > 0) {
    		token.value = newName;
	    	this.dbAdapter.open();
	    	this.dbAdapter.updateAccessToken(token.id, token.serverId, newName);
	    	this.dbAdapter.close();
    	} else {
    		Toast.makeText(this, R.string.accessNothingToChange, Toast.LENGTH_SHORT).show();
    	}
    }
    
    private void renameToken(final AccessToken token) {
    	AlertDialog.Builder alert = new AlertDialog.Builder(this);  
        
        alert.setTitle("Rename access token");  
        alert.setMessage("Input the new name of the access token.");  
          
        // Set an EditText view to get user input   
        final EditText inputEdit = new EditText(this);
        inputEdit.setText(token.value);
        inputEdit.setHint(R.string.accessAccessToken);
        alert.setView(inputEdit);
          
		alert.setPositiveButton(android.R.string.ok,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						AccessTokens.this.renameToken(token, inputEdit.getText().toString());
					}
				});
		alert.setNegativeButton(android.R.string.cancel, null);
          
        alert.show();
    }
    
    private void addToken() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);  
        
        alert.setTitle(R.string.accessAddToken);  
        alert.setMessage("Input the name of the new access token.");  
          
        // Set an EditText view to get user input   
        final EditText inputEdit = new EditText(this); 
        inputEdit.setHint(R.string.accessAccessToken); 
        alert.setView(inputEdit);
          
		alert.setPositiveButton(android.R.string.ok,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						AccessTokens.this.addToken(inputEdit.getText().toString());
					}
				});
		alert.setNegativeButton(android.R.string.cancel, null);
          
        alert.show();  
    }
    
    @Override
	public final boolean onMenuItemSelected(
		final int featureId,
		final MenuItem item) {
		switch (item.getItemId()) {
		case MENU_ADD_TOKEN:
			addToken();
			return true;
		default:
			return super.onMenuItemSelected(featureId, item);
		}
	}
    
    @Override
	protected final void onDestroy() {
		super.onDestroy();

		this.dbAdapter.close();
	}
    
    @Override
	protected void onServiceBound() {
		super.onServiceBound();
		
		this.serverID = this.mService.getServerID();
		/* Access Tokens of an existing server or global access tokens */
		if (this.serverID >= -1) {
			// load all access tokens of the serverID
			for (AccessToken token : this.dbAdapter.fetchAccessTokenByServerId(serverID)) {
				this.adapter.add(token);
			}
		} else
		/* Invalid ID o.o */
		{
			
		}
    }
}
