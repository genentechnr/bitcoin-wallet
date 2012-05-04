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

package de.schildbach.wallet.ui;

import java.math.BigInteger;
import java.util.ArrayList;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.text.ClipboardManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.Spinner;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.uri.BitcoinURI;

import de.schildbach.wallet.AddressBookProvider;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.CurrencyAmountView.Listener;
import de.schildbach.wallet.util.NfcTools;
import de.schildbach.wallet.util.QrDialog;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public final class RequestCoinsFragment extends SherlockFragment
{
	private AbstractWalletActivity activity;
	private WalletApplication application;
	private ContentResolver contentResolver;
	private Object nfcManager;
	private ClipboardManager clipboardManager;

	private ImageView qrView;
	private Bitmap qrCodeBitmap;
	private CurrencyAmountView amountView;
	private Spinner addressView;
	private CheckBox includeLabelView;
	private View nfcEnabledView;

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);
		this.activity = (AbstractWalletActivity) activity;
		application = (WalletApplication) activity.getApplication();
		contentResolver = activity.getContentResolver();

		nfcManager = activity.getSystemService(Context.NFC_SERVICE);
		clipboardManager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		final View view = inflater.inflate(R.layout.request_coins_fragment, container);

		qrView = (ImageView) view.findViewById(R.id.request_coins_qr);
		qrView.setOnClickListener(new OnClickListener()
		{
			public void onClick(final View v)
			{
				new QrDialog(activity, qrCodeBitmap).show();
			}
		});

		amountView = (CurrencyAmountView) view.findViewById(R.id.request_coins_amount);
		amountView.setListener(new Listener()
		{
			public void changed()
			{
				updateView();
			}

			public void done()
			{
			}
		});
		amountView.setContextButton(R.drawable.ic_input_calculator, new OnClickListener()
		{
			public void onClick(final View v)
			{
				final FragmentTransaction ft = getFragmentManager().beginTransaction();
				final Fragment prev = getFragmentManager().findFragmentByTag(AmountCalculatorFragment.FRAGMENT_TAG);
				if (prev != null)
					ft.remove(prev);
				ft.addToBackStack(null);
				final DialogFragment newFragment = new AmountCalculatorFragment(new AmountCalculatorFragment.Listener()
				{
					public void use(final BigInteger amount)
					{
						amountView.setAmount(amount);
					}
				});
				newFragment.show(ft, AmountCalculatorFragment.FRAGMENT_TAG);
			}
		});

		addressView = (Spinner) view.findViewById(R.id.request_coins_fragment_address);
		final ArrayList<ECKey> keys = application.getWallet().keychain;
		final WalletAddressesAdapter adapter = new WalletAddressesAdapter(activity, keys, false);
		addressView.setAdapter(adapter);
		addressView.setOnItemSelectedListener(new OnItemSelectedListener()
		{
			public void onItemSelected(final AdapterView<?> parent, final View view, final int position, final long id)
			{
				updateView();
			}

			public void onNothingSelected(final AdapterView<?> parent)
			{
			}
		});
		final Address selectedAddress = application.determineSelectedAddress();
		for (int i = 0; i < keys.size(); i++)
		{
			final Address address = keys.get(i).toAddress(Constants.NETWORK_PARAMETERS);
			if (address.equals(selectedAddress))
			{
				addressView.setSelection(i);
				break;
			}
		}

		includeLabelView = (CheckBox) view.findViewById(R.id.request_coins_fragment_include_label);
		includeLabelView.setOnCheckedChangeListener(new OnCheckedChangeListener()
		{
			public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked)
			{
				updateView();
			}
		});

		nfcEnabledView = view.findViewById(R.id.request_coins_fragment_nfc_enabled);

		return view;
	}

	@Override
	public void onResume()
	{
		super.onResume();

		updateView();
	}

	@Override
	public void onPause()
	{
		super.onPause();

		if (nfcManager != null)
			NfcTools.unpublish(nfcManager, activity);
	}

	@Override
	public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater)
	{
		inflater.inflate(R.menu.request_coins_fragment_options, menu);

		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.request_coins_options_share:
				handleShare();
				return true;

			case R.id.request_coins_options_copy:
				handleCopy();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	private void handleShare()
	{
		startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, determineRequestStr()).setType("text/plain"),
				getActivity().getString(R.string.request_coins_share_dialog_title)));
	}

	private void handleCopy()
	{
		final String request = determineRequestStr();
		clipboardManager.setText(request);
		activity.toast(R.string.request_coins_clipboard_msg);

		System.out.println("bitcoin request uri: " + request + (Constants.TEST ? " [testnet]" : ""));
	}

	private void updateView()
	{
		final String request = determineRequestStr();

		if (qrCodeBitmap != null)
			qrCodeBitmap.recycle();

		final int size = (int) (256 * getResources().getDisplayMetrics().density);
		qrCodeBitmap = WalletUtils.getQRCodeBitmap(request, size);
		qrView.setImageBitmap(qrCodeBitmap);

		if (nfcManager != null)
		{
			final boolean success = NfcTools.publishUri(nfcManager, getActivity(), request);
			if (success)
				nfcEnabledView.setVisibility(View.VISIBLE);
		}
	}

	private String determineRequestStr()
	{
		final boolean includeLabel = includeLabelView.isChecked();

		final ECKey key = application.getWallet().keychain.get(addressView.getSelectedItemPosition());
		final Address address = key.toAddress(Constants.NETWORK_PARAMETERS);
		final String label = includeLabel ? AddressBookProvider.resolveLabel(contentResolver, address.toString()) : null;
		final BigInteger amount = amountView.getAmount();

		return BitcoinURI.convertToBitcoinURI(address, amount, label, null).toString();
	}
}
