package cy.agorise.bitsybitshareswallet.fragments

import androidx.lifecycle.ViewModelProviders
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.*
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.Observer
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController

import cy.agorise.bitsybitshareswallet.R
import cy.agorise.bitsybitshareswallet.database.entities.UserAccount
import cy.agorise.bitsybitshareswallet.utils.Constants
import cy.agorise.bitsybitshareswallet.viewmodels.UserAccountViewModel
import kotlinx.android.synthetic.main.fragment_home.*
import android.os.Build
import android.content.res.ColorStateList



class HomeFragment : Fragment() {

    private lateinit var mUserAccountViewModel: UserAccountViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        setHasOptionsMenu(true)

        val nightMode = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Constants.KEY_NIGHT_MODE_ACTIVATED, false)

        // Sets the toolbar background color to primaryColor and forces shows the Bitsy icon to the left
        val toolbar: Toolbar? = activity?.findViewById(R.id.toolbar)
        toolbar?.navigationIcon = resources.getDrawable(R.drawable.ic_bitsy_logo_2, null)
        toolbar?.setBackgroundResource(if (!nightMode) R.color.colorPrimary else R.color.colorToolbarDark)

        // Sets the status bar background color to a primaryColorDark
        val window = activity?.window
        window?.statusBarColor = ContextCompat.getColor(context!!,
            if (!nightMode) R.color.colorPrimaryDark else R.color.colorStatusBarDark)

        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get version number of the last agreed license version
        val agreedLicenseVersion = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(Constants.KEY_LAST_AGREED_LICENSE_VERSION, 0)

        val userId = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Constants.KEY_CURRENT_ACCOUNT_ID, "") ?: ""

        if (agreedLicenseVersion != Constants.CURRENT_LICENSE_VERSION || userId == "") {
            findNavController().navigate(R.id.license_action)
            return
        }

        // Configure UserAccountViewModel to show the current account
        mUserAccountViewModel = ViewModelProviders.of(this).get(UserAccountViewModel::class.java)

        mUserAccountViewModel.getUserAccount(userId).observe(this, Observer<UserAccount>{ userAccount ->
            if (userAccount != null) {
                tvAccountName.text = userAccount.name
                if (userAccount.isLtm) {
                    // Add the lightning bolt to the start of the account name if it is LTM
                    tvAccountName.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        resources.getDrawable(R.drawable.ic_ltm_account, null), null, null, null
                    )
                    // Add some padding so that the lightning bolt icon is not too close to the account name text
                    tvAccountName.compoundDrawablePadding = 4
                }
            }
        })

        // Navigate to the Receive Transaction Fragment
        fabReceiveTransaction.setOnClickListener (
            Navigation.createNavigateOnClickListener(R.id.receive_action)
        )

        // Navigate to the Send Transaction Fragment without activating the camera
        fabSendTransaction.setOnClickListener(
            Navigation.createNavigateOnClickListener(R.id.send_action)
        )

        // Navigate to the Send Transaction Fragment using Navigation's SafeArgs to activate the camera
        fabSendTransactionCamera.setOnClickListener {
            val action = HomeFragmentDirections.sendActionCamera()
            action.openCamera = true
            findNavController().navigate(action)
        }

        // Configure ViewPager with PagerAdapter and TabLayout to display the Balances/NetWorth section
        val pagerAdapter = PagerAdapter(fragmentManager!!)
        viewPager.adapter = pagerAdapter
        tabLayout.setupWithViewPager(viewPager)
        // Set the pie chart icon for the third tab
        tabLayout.getTabAt(2)?.setIcon(R.drawable.ic_pie_chart)

        var icon = tabLayout.getTabAt(2)?.icon
        if (icon != null) {
            val colors: ColorStateList = if (Build.VERSION.SDK_INT >= 23) {
                resources.getColorStateList(R.color.tab_icon_selector, context?.theme)
            } else {
                resources.getColorStateList(R.color.tab_icon_selector)
            }

            icon = DrawableCompat.wrap(icon)
            DrawableCompat.setTintList(icon, colors)
        }
    }

    /**
     * Pager adapter to create the placeholder fragments
     */
    private inner class PagerAdapter internal constructor(fm: FragmentManager) : FragmentPagerAdapter(fm) {

        override fun getItem(position: Int): Fragment {
            // getItem is called to instantiate the fragment for the given page.
            return if (position == 0)
                BalancesFragment()
            else
                NetWorthFragment()
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return listOf(getString(R.string.title_balances), getString(R.string.title_net_worth), "")[position]
        }

        override fun getCount(): Int {
            return 3
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_home, menu)
    }
}