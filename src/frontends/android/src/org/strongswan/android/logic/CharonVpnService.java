/*
 * Copyright (C) 2012-2013 Tobias Brunner
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

package org.strongswan.android.logic;

import java.io.File;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnType.VpnTypeFeature;
import org.strongswan.android.logic.VpnStateService.ErrorState;
import org.strongswan.android.logic.VpnStateService.State;
import org.strongswan.android.logic.imc.ImcState;
import org.strongswan.android.logic.imc.RemediationInstruction;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.security.KeyChain;
import android.security.KeyChainException;
import android.util.Log;

public class CharonVpnService extends VpnService implements Runnable
{
	private static final String TAG = CharonVpnService.class.getSimpleName();
	public static final String LOG_FILE = "charon.log";
	public static final String PROFILE_BUNDLE_KEY = "profile";

	private String mLogFile;
	private Thread mConnectionHandler;
	private VpnProfile mCurrentProfile;
	private volatile String mCurrentCertificateAlias;
	private volatile String mCurrentUserCertificateAlias;
	private volatile String mCurrentUserCertificatePassword;
	private VpnProfile mNextProfile;
	private volatile boolean mProfileUpdated;
	private volatile boolean mTerminate;
	private volatile boolean mIsDisconnecting;
	private VpnStateService mService;
	private final Object mServiceLock = new Object();
	private final ServiceConnection mServiceConnection = new ServiceConnection() {
		@Override
		public void onServiceDisconnected(ComponentName name)
		{	/* since the service is local this is theoretically only called when the process is terminated */
			synchronized (mServiceLock)
			{
				mService = null;
			}
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service)
		{
			synchronized (mServiceLock)
			{
				mService = ((VpnStateService.LocalBinder)service).getService();
			}
			/* we are now ready to start the handler thread */
			mConnectionHandler.start();
		}
	};

	/**
	 * as defined in charonservice.h
	 */
	static final int STATE_CHILD_SA_UP = 1;
	static final int STATE_CHILD_SA_DOWN = 2;
	static final int STATE_AUTH_ERROR = 3;
	static final int STATE_PEER_AUTH_ERROR = 4;
	static final int STATE_LOOKUP_ERROR = 5;
	static final int STATE_UNREACHABLE_ERROR = 6;
	static final int STATE_GENERIC_ERROR = 7;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		if (intent != null)
		{
			Bundle bundle = intent.getExtras();
			VpnProfile profile = null;
			if (bundle != null)
			{
				profile = (VpnProfile)bundle.getParcelable(PROFILE_BUNDLE_KEY);
			}
			setNextProfile(profile);
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onCreate()
	{
		mLogFile = getFilesDir().getAbsolutePath() + File.separator + LOG_FILE;

		/* use a separate thread as main thread for charon */
		mConnectionHandler = new Thread(this);
		/* the thread is started when the service is bound */
		bindService(new Intent(this, VpnStateService.class),
					mServiceConnection, Service.BIND_AUTO_CREATE);
	}

	@Override
	public void onRevoke()
	{	/* the system revoked the rights grated with the initial prepare() call.
		 * called when the user clicks disconnect in the system's VPN dialog */
		setNextProfile(null);
	}

	@Override
	public void onDestroy()
	{
		mTerminate = true;
		setNextProfile(null);
		try
		{
			mConnectionHandler.join();
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
		if (mService != null)
		{
			unbindService(mServiceConnection);
		}
	}

	/**
	 * Set the profile that is to be initiated next. Notify the handler thread.
	 *
	 * @param profile the profile to initiate
	 */
	private void setNextProfile(VpnProfile profile)
	{
		synchronized (this)
		{
			this.mNextProfile = profile;
			mProfileUpdated = true;
			notifyAll();
		}
	}

	@Override
	public void run()
	{
		while (true)
		{
			synchronized (this)
			{
				try
				{
					while (!mProfileUpdated)
					{
						wait();
					}

					mProfileUpdated = false;
					stopCurrentConnection();
					if (mNextProfile == null)
					{
						setState(State.DISABLED);
						if (mTerminate)
						{
							break;
						}
					}
					else
					{
						mCurrentProfile = mNextProfile;
						mNextProfile = null;

						/* store this in a separate (volatile) variable to avoid
						 * a possible deadlock during deinitialization */
						mCurrentCertificateAlias = mCurrentProfile.getCertificateAlias();
						mCurrentUserCertificateAlias = mCurrentProfile.getUserCertificateAlias();
						mCurrentUserCertificatePassword = mCurrentProfile.getUserCertificatePassword();

						startConnection(mCurrentProfile);
						mIsDisconnecting = false;

						BuilderAdapter builder = new BuilderAdapter(mCurrentProfile.getName());
						if (initializeCharon(builder, mLogFile, mCurrentProfile.getVpnType().has(VpnTypeFeature.BYOD)))
						{
							Log.i(TAG, "charon started");
							initiate(mCurrentProfile.getVpnType().getIdentifier(),
									 mCurrentProfile.getGateway(), mCurrentProfile.getUsername(),
									 mCurrentProfile.getPassword());
						}
						else
						{
							Log.e(TAG, "failed to start charon");
							setError(ErrorState.GENERIC_ERROR);
							setState(State.DISABLED);
							mCurrentProfile = null;
						}
					}
				}
				catch (InterruptedException ex)
				{
					stopCurrentConnection();
					setState(State.DISABLED);
				}
			}
		}
	}

	/**
	 * Stop any existing connection by deinitializing charon.
	 */
	private void stopCurrentConnection()
	{
		synchronized (this)
		{
			if (mCurrentProfile != null)
			{
				setState(State.DISCONNECTING);
				mIsDisconnecting = true;
				deinitializeCharon();
				Log.i(TAG, "charon stopped");
				mCurrentProfile = null;
			}
		}
	}

	/**
	 * Notify the state service about a new connection attempt.
	 * Called by the handler thread.
	 *
	 * @param profile currently active VPN profile
	 */
	private void startConnection(VpnProfile profile)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.startConnection(profile);
			}
		}
	}

	/**
	 * Update the current VPN state on the state service. Called by the handler
	 * thread and any of charon's threads.
	 *
	 * @param state current state
	 */
	private void setState(State state)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.setState(state);
			}
		}
	}

	/**
	 * Set an error on the state service. Called by the handler thread and any
	 * of charon's threads.
	 *
	 * @param error error state
	 */
	private void setError(ErrorState error)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.setError(error);
			}
		}
	}

	/**
	 * Set the IMC state on the state service. Called by the handler thread and
	 * any of charon's threads.
	 *
	 * @param state IMC state
	 */
	private void setImcState(ImcState state)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.setImcState(state);
			}
		}
	}

	/**
	 * Set an error on the state service. Called by the handler thread and any
	 * of charon's threads.
	 *
	 * @param error error state
	 */
	private void setErrorDisconnect(ErrorState error)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				if (!mIsDisconnecting)
				{
					mService.setError(error);
				}
			}
		}
	}

	/**
	 * Updates the state of the current connection.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param status new state
	 */
	public void updateStatus(int status)
	{
		/* For our purposes if the child sa goes down it should stay down.
		   Since we changed the dpd_action and close_action from ACTION_RESTART
		   to ACTION_NONE don't transition to CONNECTING instead trigger a disconnect. 
		   Additionally, if we get an error back, because the policy is not to
		   automatically reconnect we should also trigger a disconnect/shutdown. */
		switch (status)
		{
			case STATE_CHILD_SA_DOWN:
				setNextProfile(null);
				break;
			case STATE_CHILD_SA_UP:
				setState(State.CONNECTED);
				break;
			case STATE_AUTH_ERROR:
				setErrorDisconnect(ErrorState.AUTH_FAILED);
				setNextProfile(null);
				break;
			case STATE_PEER_AUTH_ERROR:
				setErrorDisconnect(ErrorState.PEER_AUTH_FAILED);
				setNextProfile(null);
				break;
			case STATE_LOOKUP_ERROR:
				setErrorDisconnect(ErrorState.LOOKUP_FAILED);
				setNextProfile(null);
				break;
			case STATE_UNREACHABLE_ERROR:
				setErrorDisconnect(ErrorState.UNREACHABLE);
				setNextProfile(null);
				break;
			case STATE_GENERIC_ERROR:
				setErrorDisconnect(ErrorState.GENERIC_ERROR);
				setNextProfile(null);
				break;
			default:
				Log.e(TAG, "Unknown status code received");
				break;
		}
	}

	/**
	 * Updates the IMC state of the current connection.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param value new state
	 */
	public void updateImcState(int value)
	{
		ImcState state = ImcState.fromValue(value);
		if (state != null)
		{
			setImcState(state);
		}
	}

	/**
	 * Add a remediation instruction to the VPN state service.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param xml XML text
	 */
	public void addRemediationInstruction(String xml)
	{
		for (RemediationInstruction instruction : RemediationInstruction.fromXml(xml))
		{
			synchronized (mServiceLock)
			{
				if (mService != null)
				{
					mService.addRemediationInstruction(instruction);
				}
			}
		}
	}

	/**
	 * Function called via JNI to generate a list of DER encoded CA certificates
	 * as byte array.
	 *
	 * @return a list of DER encoded CA certificates
	 */
	private byte[][] getTrustedCertificates()
	{
		ArrayList<byte[]> certs = new ArrayList<byte[]>();
		TrustedCertificateManager certman = TrustedCertificateManager.getInstance();
		try
		{
			String alias = this.mCurrentCertificateAlias;
			if (alias != null)
			{
				X509Certificate cert = certman.getCACertificateFromAlias(alias);
				if (cert == null)
				{
					return null;
				}
				certs.add(cert.getEncoded());
			}
			else
			{
				for (X509Certificate cert : certman.getAllCACertificates().values())
				{
					certs.add(cert.getEncoded());
				}
			}
		}
		catch (CertificateEncodingException e)
		{
			e.printStackTrace();
			return null;
		}
		return certs.toArray(new byte[certs.size()][]);
	}

	/**
	 * Function called via JNI to get a list containing the DER encoded certificates
	 * of the user selected certificate chain (beginning with the user certificate).
	 *
	 * Since this method is called from a thread of charon's thread pool we are safe
	 * to call methods on KeyChain directly.
	 *
	 * @return list containing the certificates (first element is the user certificate)
	 * @throws InterruptedException
	 * @throws KeyChainException
	 * @throws CertificateEncodingException
	 */
	private byte[][] getUserCertificate() throws KeyStoreException, CertificateEncodingException
	{
		ArrayList<byte[]> encodings = new ArrayList<byte[]>();
		Certificate[] chain = UserCredentialManager.getInstance().getUserCertificateChain(mCurrentUserCertificateAlias);
		if (chain == null || chain.length == 0)
		{
			return null;
		}
		for (Certificate cert : chain)
		{
			encodings.add(cert.getEncoded());
		}
		return encodings.toArray(new byte[encodings.size()][]);
	}

	/**
	 * Function called via JNI to get the private key the user selected.
	 *
	 * Since this method is called from a thread of charon's thread pool we are safe
	 * to call methods on KeyChain directly.
	 *
	 * @return the private key
	 * @throws InterruptedException
	 * @throws KeyChainException
	 * @throws CertificateEncodingException
	 */
	private PrivateKey getUserKey() throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException
	{
		return UserCredentialManager.getInstance().getUserKey(mCurrentUserCertificateAlias, mCurrentUserCertificatePassword.toCharArray());
	}

	/**
	 * Initialization of charon, provided by libandroidbridge.so
	 *
	 * @param builder BuilderAdapter for this connection
	 * @param logfile absolute path to the logfile
	 * @param boyd enable BYOD features
	 * @return TRUE if initialization was successful
	 */
	public native boolean initializeCharon(BuilderAdapter builder, String logfile, boolean byod);

	/**
	 * Deinitialize charon, provided by libandroidbridge.so
	 */
	public native void deinitializeCharon();

	/**
	 * Initiate VPN, provided by libandroidbridge.so
	 */
	public native void initiate(String type, String gateway, String username, String password);

	/**
	 * Adapter for VpnService.Builder which is used to access it safely via JNI.
	 * There is a corresponding C object to access it from native code.
	 */
	public class BuilderAdapter
	{
		private final String mName;
		private VpnService.Builder mBuilder;
		private BuilderCache mCache;
		private BuilderCache mEstablishedCache;

		public BuilderAdapter(String name)
		{
			mName = name;
			mBuilder = createBuilder(name);
			mCache = new BuilderCache();
		}

		private VpnService.Builder createBuilder(String name)
		{
			VpnService.Builder builder = new CharonVpnService.Builder();
			builder.setSession(mName);
			return builder;
		}

		public synchronized boolean addAddress(String address, int prefixLength)
		{
			try
			{
				mBuilder.addAddress(address, prefixLength);
				mCache.addAddress(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addDnsServer(String address)
		{
			try
			{
				mBuilder.addDnsServer(address);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addRoute(String address, int prefixLength)
		{
			try
			{
				mBuilder.addRoute(address, prefixLength);
				mCache.addRoute(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addSearchDomain(String domain)
		{
			try
			{
				mBuilder.addSearchDomain(domain);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean setMtu(int mtu)
		{
			try
			{
				mBuilder.setMtu(mtu);
				mCache.setMtu(mtu);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized int establish()
		{
			ParcelFileDescriptor fd;
			try
			{
				fd = mBuilder.establish();
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				return -1;
			}
			if (fd == null)
			{
				return -1;
			}
			/* now that the TUN device is created we don't need the current
			 * builder anymore, but we might need another when reestablishing */
			mBuilder = createBuilder(mName);
			mEstablishedCache = mCache;
			mCache = new BuilderCache();
			return fd.detachFd();
		}

		public synchronized int establishNoDns()
		{
			ParcelFileDescriptor fd;

			if (mEstablishedCache == null)
			{
				return -1;
			}
			try
			{
				Builder builder = createBuilder(mName);
				mEstablishedCache.applyData(builder);
				fd = builder.establish();
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				return -1;
			}
			if (fd == null)
			{
				return -1;
			}
			return fd.detachFd();
		}
	}

	/**
	 * Cache non DNS related information so we can recreate the builder without
	 * that information when reestablishing IKE_SAs
	 */
	public class BuilderCache
	{
		private final List<PrefixedAddress> mAddresses = new ArrayList<PrefixedAddress>();
		private final List<PrefixedAddress> mRoutes = new ArrayList<PrefixedAddress>();
		private int mMtu;

		public void addAddress(String address, int prefixLength)
		{
			mAddresses.add(new PrefixedAddress(address, prefixLength));
		}

		public void addRoute(String address, int prefixLength)
		{
			mRoutes.add(new PrefixedAddress(address, prefixLength));
		}

		public void setMtu(int mtu)
		{
			mMtu = mtu;
		}

		public void applyData(VpnService.Builder builder)
		{
			for (PrefixedAddress address : mAddresses)
			{
				builder.addAddress(address.mAddress, address.mPrefix);
			}
			for (PrefixedAddress route : mRoutes)
			{
				builder.addRoute(route.mAddress, route.mPrefix);
			}
			builder.setMtu(mMtu);
		}

		private class PrefixedAddress
		{
			public String mAddress;
			public int mPrefix;

			public PrefixedAddress(String address, int prefix)
			{
				this.mAddress = address;
				this.mPrefix = prefix;
			}
		}
	}

	/*
	 * The libraries are extracted to /data/data/org.strongswan.android/...
	 * during installation.
	 */
	static
	{
		System.loadLibrary("strongswan");
		System.loadLibrary("tncif");
		System.loadLibrary("tnccs");
		System.loadLibrary("imcv");
		System.loadLibrary("hydra");
		System.loadLibrary("charon");
		System.loadLibrary("ipsec");
		System.loadLibrary("androidbridge");
	}
}
