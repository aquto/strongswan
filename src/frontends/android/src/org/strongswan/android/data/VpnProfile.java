/*
 * Copyright (C) 2012 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
 * Hochschule fuer Technik Rapperswil
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.strongswan.android.data;

import android.os.Parcelable;
import android.os.Parcel;

public class VpnProfile implements Cloneable, Parcelable
{
	private String mName, mGateway, mUsername, mPassword, mCertificate, mUserCertificate, mUserCertificatePassword;
	private VpnType mVpnType;
	private long mId = -1;


	public VpnProfile() {
	}

	public long getId()
	{
		return mId;
	}

	public void setId(long id)
	{
		this.mId = id;
	}

	public String getName()
	{
		return mName;
	}

	public void setName(String name)
	{
		this.mName = name;
	}

	public String getGateway()
	{
		return mGateway;
	}

	public void setGateway(String gateway)
	{
		this.mGateway = gateway;
	}

	public VpnType getVpnType()
	{
		return mVpnType;
	}

	public void setVpnType(VpnType type)
	{
		this.mVpnType = type;
	}

	public String getUsername()
	{
		return mUsername;
	}

	public void setUsername(String username)
	{
		this.mUsername = username;
	}

	public String getPassword()
	{
		return mPassword;
	}

	public void setPassword(String password)
	{
		this.mPassword = password;
	}

	public String getCertificateAlias()
	{
		return mCertificate;
	}

	public void setCertificateAlias(String alias)
	{
		this.mCertificate = alias;
	}

	public String getUserCertificateAlias()
	{
		return mUserCertificate;
	}

	public void setUserCertificateAlias(String alias)
	{
		this.mUserCertificate = alias;
	}

	public String getUserCertificatePassword()
	{
		return mUserCertificatePassword;
	}

	public void setUserCertificatePassword(String password)
	{
		this.mUserCertificatePassword = password;
	}

	@Override
	public String toString()
	{
		return mName;
	}

	@Override
	public boolean equals(Object o)
	{
		if (o != null && o instanceof VpnProfile)
		{
			return this.mId == ((VpnProfile)o).getId();
		}
		return false;
	}

	@Override
	public VpnProfile clone()
	{
		try
		{
			return (VpnProfile)super.clone();
		}
		catch (CloneNotSupportedException e)
		{
			throw new AssertionError();
		}
	}

	protected VpnProfile(Parcel in) {
		mName = in.readString();
		mGateway = in.readString();
		mUsername = in.readString();
		mPassword = in.readString();
		mCertificate = in.readString();
		mUserCertificate = in.readString();
		mUserCertificatePassword = in.readString();
		String vpnTypeId = (String)in.readValue(null);
		if(vpnTypeId != null)
			mVpnType = VpnType.fromIdentifier(vpnTypeId);
		mId = in.readLong();
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(mName);
		dest.writeString(mGateway);
		dest.writeString(mUsername);
		dest.writeString(mPassword);
		dest.writeString(mCertificate);
		dest.writeString(mUserCertificate);
		dest.writeString(mUserCertificatePassword);
		String vpnTypeId = (mVpnType != null ? mVpnType.getIdentifier() : null); 
		dest.writeValue(vpnTypeId);
		dest.writeLong(mId);
	}

	@SuppressWarnings("unused")
	public static final Parcelable.Creator<VpnProfile> CREATOR = new Parcelable.Creator<VpnProfile>() {
		@Override
		public VpnProfile createFromParcel(Parcel in) {
			return new VpnProfile(in);
		}

		@Override
		public VpnProfile[] newArray(int size) {
			return new VpnProfile[size];
		}
	};
}
