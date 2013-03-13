/*
 * Copyright 2013 the original author or authors.
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

package de.schildbach.wallet.ui;

import java.util.List;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockListFragment;
import com.google.bitcoin.core.Block;
import com.google.bitcoin.core.StoredBlock;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public final class BlockListFragment extends SherlockListFragment implements LoaderCallbacks<List<StoredBlock>>
{
	private AbstractWalletActivity activity;
	private BlockchainService service;
	private ArrayAdapter<StoredBlock> adapter;

	private static final int ID_BLOCK_LOADER = 0;

	private static final int MAX_BLOCKS = 32;

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
	}

	@Override
	public void onActivityCreated(final Bundle savedInstanceState)
	{
		super.onActivityCreated(savedInstanceState);

		activity.bindService(new Intent(activity, BlockchainServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		adapter = new ArrayAdapter<StoredBlock>(activity, 0)
		{
			@Override
			public View getView(final int position, View row, final ViewGroup parent)
			{
				if (row == null)
					row = getLayoutInflater(null).inflate(R.layout.block_list_row, null);

				final StoredBlock storedBlock = getItem(position);
				final Block header = storedBlock.getHeader();

				final TextView rowHeight = (TextView) row.findViewById(R.id.block_list_row_height);
				final int height = storedBlock.getHeight();
				rowHeight.setText(Integer.toString(height));

				final TextView rowTime = (TextView) row.findViewById(R.id.block_list_row_time);
				final long timeMs = header.getTimeSeconds() * 1000;
				rowTime.setText(DateUtils.getRelativeDateTimeString(activity, timeMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0));

				final TextView rowHash = (TextView) row.findViewById(R.id.block_list_row_hash);
				rowHash.setText(WalletUtils.formatHash(null, header.getHashAsString(), 8, 0, ' '));

				return row;
			}
		};
		setListAdapter(adapter);
	}

	@Override
	public void onResume()
	{
		super.onResume();

		activity.registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));

		adapter.notifyDataSetChanged();
	}

	@Override
	public void onPause()
	{
		activity.unregisterReceiver(tickReceiver);

		super.onPause();
	}

	@Override
	public void onDestroy()
	{
		activity.unbindService(serviceConnection);

		super.onDestroy();
	}

	@Override
	public void onListItemClick(final ListView l, final View v, final int position, final long id)
	{
		final StoredBlock storedBlock = adapter.getItem(position);
		final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BLOCKEXPLORER_BASE_URL + "block/"
				+ storedBlock.getHeader().getHashAsString()));
		startActivity(intent);
	}

	private final ServiceConnection serviceConnection = new ServiceConnection()
	{
		public void onServiceConnected(final ComponentName name, final IBinder binder)
		{
			service = ((BlockchainServiceImpl.LocalBinder) binder).getService();

			getLoaderManager().initLoader(ID_BLOCK_LOADER, null, BlockListFragment.this);
		}

		public void onServiceDisconnected(final ComponentName name)
		{
			getLoaderManager().destroyLoader(ID_BLOCK_LOADER);

			service = null;
		}
	};

	private final BroadcastReceiver tickReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(final Context context, final Intent intent)
		{
			adapter.notifyDataSetChanged();
		}
	};

	public Loader<List<StoredBlock>> onCreateLoader(final int id, final Bundle args)
	{
		return new BlockLoader(activity, service);
	}

	public void onLoadFinished(final Loader<List<StoredBlock>> loader, final List<StoredBlock> peers)
	{
		adapter.clear();

		if (peers != null)
			for (final StoredBlock peer : peers)
				adapter.add(peer);
	}

	public void onLoaderReset(final Loader<List<StoredBlock>> loader)
	{
		adapter.clear();
	}

	private static class BlockLoader extends AsyncTaskLoader<List<StoredBlock>>
	{
		private Context context;
		private BlockchainService service;

		private BlockLoader(final Context context, final BlockchainService service)
		{
			super(context);

			this.context = context;
			this.service = service;
		}

		@Override
		protected void onStartLoading()
		{
			super.onStartLoading();

			context.registerReceiver(broadcastReceiver, new IntentFilter(BlockchainService.ACTION_BLOCKCHAIN_STATE));
		}

		@Override
		protected void onStopLoading()
		{
			context.unregisterReceiver(broadcastReceiver);

			super.onStopLoading();
		}

		@Override
		public List<StoredBlock> loadInBackground()
		{
			return service.getRecentBlocks(MAX_BLOCKS);
		}

		private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver()
		{
			@Override
			public void onReceive(final Context context, final Intent intent)
			{
				forceLoad();
			}
		};
	}
}
