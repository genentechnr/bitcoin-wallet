/*
 * Copyright 2011-2012 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.DumpedPrivateKey;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Wallet.AutosaveEventListener;

import de.schildbach.wallet.util.ErrorReporter;
import de.schildbach.wallet.util.Iso8601Format;
import de.schildbach.wallet.util.StrictModeWrapper;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class WalletApplication extends Application
{
	private File walletFile;
	private Wallet wallet;

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	@Override
	public void onCreate()
	{
		try
		{
			StrictModeWrapper.init();
		}
		catch (final Error x)
		{
			System.out.println("StrictMode not available");
		}

		System.out.println(getClass().getName() + ".onCreate()");

		super.onCreate();

		ErrorReporter.getInstance().init(this);

		walletFile = getFileStreamPath(Constants.WALLET_FILENAME_PROTOBUF);

		migrateWalletToProtobuf();

		loadWalletFromProtobuf();

		backupKeys();

		wallet.autosaveToFile(walletFile, 1, TimeUnit.SECONDS, new WalletAutosaveEventListener());
	}

	private static final class WalletAutosaveEventListener implements AutosaveEventListener
	{
		public boolean caughtException(final Throwable t)
		{
			throw new Error(t);
		}

		public void onBeforeAutoSave(final File file)
		{
		}

		public void onAfterAutoSave(final File file)
		{
			// make wallets world accessible in test mode
			if (Constants.TEST)
				chmod(file, 0777);
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		public void chmod(final File path, final int mode)
		{
			try
			{
				final Class fileUtils = Class.forName("android.os.FileUtils");
				final Method setPermissions = fileUtils.getMethod("setPermissions", String.class, int.class, int.class, int.class);
				setPermissions.invoke(null, path.getAbsolutePath(), mode, -1, -1);
			}
			catch (final Exception x)
			{
				x.printStackTrace();
			}
		}
	}

	public Wallet getWallet()
	{
		return wallet;
	}

	private void migrateWalletToProtobuf()
	{
		final File oldWalletFile = getFileStreamPath(Constants.WALLET_FILENAME);

		if (oldWalletFile.exists())
		{
			System.out.println("found wallet to migrate");

			final long start = System.currentTimeMillis();

			// read
			wallet = restoreWalletFromBackup();

			try
			{
				// write
				protobufSerializeWallet(wallet);

				// delete
				oldWalletFile.delete();

				System.out.println("wallet migrated: '" + oldWalletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
			}
			catch (final IOException x)
			{
				throw new Error("cannot migrate wallet", x);
			}
		}
	}

	private void loadWalletFromProtobuf()
	{
		if (walletFile.exists())
		{
			final long start = System.currentTimeMillis();

			try
			{
				wallet = Wallet.loadFromFile(walletFile);
			}
			catch (final IOException x)
			{
				x.printStackTrace();

				Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

				wallet = restoreWalletFromBackup();
			}
			catch (final IllegalStateException x)
			{
				x.printStackTrace();

				Toast.makeText(WalletApplication.this, x.getClass().getName(), Toast.LENGTH_LONG).show();

				wallet = restoreWalletFromBackup();
			}

			if (!wallet.isConsistent())
			{
				Toast.makeText(this, "inconsistent wallet: " + walletFile, Toast.LENGTH_LONG).show();

				wallet = restoreWalletFromBackup();
			}

			if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS))
				throw new Error("bad wallet network parameters: " + wallet.getParams().getId());

			System.out.println("wallet loaded from: '" + walletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
		}
		else
		{
			try
			{
				wallet = restoreWalletFromSnapshot();
			}
			catch (final FileNotFoundException x)
			{
				wallet = new Wallet(Constants.NETWORK_PARAMETERS);
				wallet.addKey(new ECKey());

				try
				{
					protobufSerializeWallet(wallet);
					System.out.println("wallet created: '" + walletFile + "'");
				}
				catch (final IOException x2)
				{
					throw new Error("wallet cannot be created", x2);
				}
			}
		}
	}

	private Wallet restoreWalletFromBackup()
	{
		try
		{
			final Wallet wallet = new Wallet(Constants.NETWORK_PARAMETERS);
			final BufferedReader in = new BufferedReader(new InputStreamReader(openFileInput(Constants.WALLET_KEY_BACKUP_BASE58), UTF_8));

			while (true)
			{
				final String line = in.readLine();
				if (line == null)
					break;

				final String[] parts = line.split(" ");

				final ECKey key = new DumpedPrivateKey(Constants.NETWORK_PARAMETERS, parts[0]).getKey();
				key.setCreationTimeSeconds(parts.length >= 2 ? Iso8601Format.parseDateTimeT(parts[1]).getTime() / 1000 : 0);

				wallet.addKey(key);
			}

			in.close();

			final File file = new File(getDir("blockstore", Context.MODE_WORLD_READABLE | Context.MODE_WORLD_WRITEABLE),
					Constants.BLOCKCHAIN_FILENAME);
			file.delete();

			Toast.makeText(this, R.string.toast_wallet_reset, Toast.LENGTH_LONG).show();

			return wallet;
		}
		catch (final IOException x)
		{
			throw new RuntimeException(x);
		}
		catch (final AddressFormatException x)
		{
			throw new RuntimeException(x);
		}
		catch (final ParseException x)
		{
			throw new RuntimeException(x);
		}
	}

	private Wallet restoreWalletFromSnapshot() throws FileNotFoundException
	{
		try
		{
			final Wallet wallet = new Wallet(Constants.NETWORK_PARAMETERS);
			final BufferedReader in = new BufferedReader(new InputStreamReader(getAssets().open(Constants.WALLET_KEY_BACKUP_SNAPSHOT), UTF_8));

			while (true)
			{
				final String line = in.readLine();
				if (line == null)
					break;

				final String[] parts = line.split(" ");

				final ECKey key = new DumpedPrivateKey(Constants.NETWORK_PARAMETERS, parts[0]).getKey();
				key.setCreationTimeSeconds(parts.length >= 2 ? Iso8601Format.parseDateTimeT(parts[1]).getTime() / 1000 : 0);

				wallet.addKey(key);
			}

			in.close();

			System.out.println("wallet restored from snapshot");

			return wallet;
		}
		catch (final FileNotFoundException x)
		{
			throw x;
		}
		catch (final IOException x)
		{
			throw new RuntimeException(x);
		}
		catch (final AddressFormatException x)
		{
			throw new RuntimeException(x);
		}
		catch (final ParseException x)
		{
			throw new RuntimeException(x);
		}
	}

	public void addNewKeyToWallet()
	{
		wallet.addKey(new ECKey());

		backupKeys();
	}

	public void saveWallet()
	{
		try
		{
			protobufSerializeWallet(wallet);
		}
		catch (final IOException x)
		{
			throw new RuntimeException(x);
		}
	}

	private void protobufSerializeWallet(final Wallet wallet) throws IOException
	{
		final long start = System.currentTimeMillis();

		wallet.saveToFile(walletFile);

		System.out.println("wallet saved to: '" + walletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
	}

	private void writeKeys(final OutputStream os) throws IOException
	{
		final DateFormat format = Iso8601Format.newDateTimeFormatT();
		final Writer out = new OutputStreamWriter(os, UTF_8);

		for (final ECKey key : wallet.keychain)
		{
			out.write(key.getPrivateKeyEncoded(Constants.NETWORK_PARAMETERS).toString());
			if (key.getCreationTimeSeconds() != 0)
			{
				out.write(' ');
				out.write(format.format(new Date(key.getCreationTimeSeconds() * 1000)));
			}
			out.write('\n');
		}

		out.close();
	}

	private void backupKeys()
	{
		try
		{
			writeKeys(openFileOutput(Constants.WALLET_KEY_BACKUP_BASE58, Context.MODE_PRIVATE));
		}
		catch (final IOException x)
		{
			x.printStackTrace();
		}

		try
		{
			final long MS_PER_DAY = 24 * 60 * 60 * 1000;
			final String filename = String.format("%s.%02d", Constants.WALLET_KEY_BACKUP_BASE58, (System.currentTimeMillis() / MS_PER_DAY) % 100l);
			writeKeys(openFileOutput(filename, Context.MODE_PRIVATE));
		}
		catch (final IOException x)
		{
			x.printStackTrace();
		}
	}

	public Address determineSelectedAddress()
	{
		final ArrayList<ECKey> keychain = wallet.keychain;

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		final String defaultAddress = keychain.get(0).toAddress(Constants.NETWORK_PARAMETERS).toString();
		final String selectedAddress = prefs.getString(Constants.PREFS_KEY_SELECTED_ADDRESS, defaultAddress);

		// sanity check
		for (final ECKey key : keychain)
		{
			final Address address = key.toAddress(Constants.NETWORK_PARAMETERS);
			if (address.toString().equals(selectedAddress))
				return address;
		}

		throw new IllegalStateException("address not in keychain: " + selectedAddress);
	}

	public final int applicationVersionCode()
	{
		try
		{
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
		}
		catch (NameNotFoundException x)
		{
			return 0;
		}
	}

	public final String applicationVersionName()
	{
		try
		{
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		}
		catch (NameNotFoundException x)
		{
			return "unknown";
		}
	}
}
