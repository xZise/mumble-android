package org.pcgod.mumbleclient.app;

public class AccessToken {
	public final String value;
	public final long id;
	public final long serverId;
	
	public AccessToken(String value, long id, long serverId) {
		this.value = value;
		this.id = id;
		this.serverId = serverId;
	}
	
	public String toString() {
		return this.value;
	}
}
