/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.DISABLE_CLOCK;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS;
import static android.app.StatusBarManager.DISABLE_SYSTEM_INFO;

import android.annotation.Nullable;
import android.app.Fragment;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.ImageSwitcher;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.phone.TickerView;
import com.android.systemui.statusbar.policy.EncryptionHelper;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import android.widget.ImageView;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

/**
 * Contains the collapsed status bar and handles hiding/showing based on disable flags
 * and keyguard state. Also manages lifecycle to make sure the views it contains are being
 * updated by the StatusBarIconController and DarkIconManager while it is attached.
 */
public class CollapsedStatusBarFragment extends Fragment implements CommandQueue.Callbacks,
        StatusBarStateController.StateListener {

    public static final String TAG = "CollapsedStatusBarFragment";
    private static final String EXTRA_PANEL_STATE = "panel_state";
    public static final String STATUS_BAR_ICON_MANAGER_TAG = "status_bar_icon_manager";
    public static final int FADE_IN_DURATION = 320;
    public static final int FADE_IN_DELAY = 50;
    private PhoneStatusBarView mStatusBar;
    private StatusBarStateController mStatusBarStateController;
    private KeyguardMonitor mKeyguardMonitor;
    private NetworkController mNetworkController;
    private LinearLayout mSystemIconArea;
    private LinearLayout mCenterClockLayout;
    private View mClockView;
    private View mRightClock;
    private int mClockStyle;
    private View mNotificationIconAreaInner;
    private View mCenteredIconArea;
    private int mDisabled1;
    private StatusBar mStatusBarComponent;
    private DarkIconManager mDarkIconManager;
    private View mOperatorNameFrame;
    private CommandQueue mCommandQueue;
    private final Handler mHandler = new Handler();
    private ContentResolver mContentResolver;
    private boolean mShowClock = true;

    private ImageView mDerpQuestLogo;
    private ImageView mDerpQuestLogoRight;
    private int mLogoStyle;
    private int mShowLogo;

    // Statusbar Weather Image
    private View mWeatherImageView;
    private View mWeatherTextView;
    private int mShowWeather;
    private boolean mWeatherInHeaderView;

    // custom carrier label
    private View mCustomCarrierLabel;
    private int mShowCarrierLabel;
    private boolean mHasCarrierLabel;

    private class SettingsObserver extends ContentObserver {
       SettingsObserver(Handler handler) {
           super(handler);
       }

       void observe() {
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_STYLE),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO_STYLE),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_CARRIER),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION),
                    false, this, UserHandle.USER_ALL);
       }

       @Override
       public void onChange(boolean selfChange, Uri uri) {
            if ((uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO))) ||
                (uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_STYLE)))){
                 updateLogoSettings(true);
            } else if ((uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP))) ||
                (uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION)))){
        }
            updateSettings(true);
       }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    // Notification ticker
    private int mTickerEnabled;
    private View mTickerViewFromStub;

    private SignalCallback mSignalCallback = new SignalCallback() {
        @Override
        public void setIsAirplaneMode(NetworkController.IconState icon) {
            mCommandQueue.recomputeDisableFlags(getContext().getDisplayId(), true /* animate */);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContentResolver = getContext().getContentResolver();
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mNetworkController = Dependency.get(NetworkController.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mStatusBarComponent = SysUiServiceProvider.getComponent(getContext(), StatusBar.class);
        mCommandQueue = SysUiServiceProvider.getComponent(getContext(), CommandQueue.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status_bar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStatusBar = (PhoneStatusBarView) view;
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_PANEL_STATE)) {
            mStatusBar.restoreHierarchyState(
                    savedInstanceState.getSparseParcelableArray(EXTRA_PANEL_STATE));
        }
        mDarkIconManager = new DarkIconManager(view.findViewById(R.id.statusIcons));
        mDarkIconManager.setShouldLog(true);
        Dependency.get(StatusBarIconController.class).addIconGroup(mDarkIconManager);
        mSystemIconArea = mStatusBar.findViewById(R.id.system_icon_area);
        mClockView = mStatusBar.findViewById(R.id.clock);
        mCenterClockLayout = (LinearLayout) mStatusBar.findViewById(R.id.center_clock_layout);
        mRightClock = mStatusBar.findViewById(R.id.right_clock);
        mCustomCarrierLabel = mStatusBar.findViewById(R.id.statusbar_carrier_text);
        mDerpQuestLogo = mStatusBar.findViewById(R.id.status_bar_logo);
        mDerpQuestLogoRight = mStatusBar.findViewById(R.id.status_bar_logo_right);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mDerpQuestLogo);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mDerpQuestLogoRight);
        mWeatherTextView = mStatusBar.findViewById(R.id.weather_temp);
        mWeatherImageView = mStatusBar.findViewById(R.id.weather_image);
        updateLogoSettings(false);
        showSystemIconArea(false);
        initEmergencyCryptkeeperText();
        animateHide(mClockView, false, false);
        initOperatorName();
        mSettingsObserver.observe();
        updateSettings(false);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        SparseArray<Parcelable> states = new SparseArray<>();
        mStatusBar.saveHierarchyState(states);
        outState.putSparseParcelableArray(EXTRA_PANEL_STATE, states);
    }

    @Override
    public void onResume() {
        super.onResume();
        mCommandQueue.addCallback(this);
        mStatusBarStateController.addCallback(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Dependency.get(StatusBarIconController.class).removeIconGroup(mDarkIconManager);
        if (mNetworkController.hasEmergencyCryptKeeperText()) {
            mNetworkController.removeCallback(mSignalCallback);
        }
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mDerpQuestLogo);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mDerpQuestLogoRight);
    }

    public void initNotificationIconArea(NotificationIconAreaController
            notificationIconAreaController) {
        ViewGroup notificationIconArea = mStatusBar.findViewById(R.id.notification_icon_area);
        mNotificationIconAreaInner =
                notificationIconAreaController.getNotificationInnerAreaView();
        if (mNotificationIconAreaInner.getParent() != null) {
            ((ViewGroup) mNotificationIconAreaInner.getParent())
                    .removeView(mNotificationIconAreaInner);
        }
        notificationIconArea.addView(mNotificationIconAreaInner);

        ViewGroup statusBarCenteredIconArea = mStatusBar.findViewById(R.id.centered_icon_area);
        mCenteredIconArea = notificationIconAreaController.getCenteredNotificationAreaView();
        if (mCenteredIconArea.getParent() != null) {
            ((ViewGroup) mCenteredIconArea.getParent())
                    .removeView(mCenteredIconArea);
        }
        statusBarCenteredIconArea.addView(mCenteredIconArea);

        // Default to showing until we know otherwise.
        showNotificationIconArea(false);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        state1 = adjustDisableFlags(state1);
        final int old1 = mDisabled1;
        final int diff1 = state1 ^ old1;
        mDisabled1 = state1;
        if ((diff1 & DISABLE_SYSTEM_INFO) != 0) {
            if ((state1 & DISABLE_SYSTEM_INFO) != 0) {
                hideSystemIconArea(animate);
                hideOperatorName(animate);
                hideSbWeather(animate);
            } else {
                showSystemIconArea(animate);
                showOperatorName(animate);
                showSbWeather(animate);
            }
        }
        if ((diff1 & DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state1 & DISABLE_NOTIFICATION_ICONS) != 0) {
                hideNotificationIconArea(animate);
                hideCarrierName(animate);
                animateHide(mClockView, animate, mClockStyle == 0);
            } else {
                showNotificationIconArea(animate);
                updateClockStyle(animate);
                showCarrierName(animate);
            }
        }
    }

    protected int adjustDisableFlags(int state) {
        boolean headsUpVisible = mStatusBarComponent.headsUpShouldBeVisible();
        if (headsUpVisible) {
            state |= DISABLE_CLOCK;
        }

        if (!mKeyguardMonitor.isLaunchTransitionFadingAway()
                && !mKeyguardMonitor.isKeyguardFadingAway()
                && shouldHideNotificationIcons()
                && !(mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        && headsUpVisible)) {
            state |= DISABLE_NOTIFICATION_ICONS;
            state |= DISABLE_SYSTEM_INFO;
            state |= DISABLE_CLOCK;
        }


        if (mNetworkController != null && EncryptionHelper.IS_DATA_ENCRYPTED) {
            if (mNetworkController.hasEmergencyCryptKeeperText()) {
                state |= DISABLE_NOTIFICATION_ICONS;
            }
            if (!mNetworkController.isRadioOn()) {
                state |= DISABLE_SYSTEM_INFO;
            }
        }

        // The shelf will be hidden when dozing with a custom clock, we must show notification
        // icons in this occasion.
        if (mStatusBarStateController.isDozing()
                && mStatusBarComponent.getPanel().hasCustomClock()) {
            state |= DISABLE_CLOCK | DISABLE_SYSTEM_INFO;
        }

        return state;
    }

    private boolean shouldHideNotificationIcons() {
        if (!mStatusBar.isClosed() && mStatusBarComponent.hideStatusBarIconsWhenExpanded()) {
            return true;
        }
        if (mStatusBarComponent.hideStatusBarIconsForBouncer()) {
            return true;
        }
        return false;
    }

    public void hideSystemIconArea(boolean animate) {
        animateHide(mCenterClockLayout, animate, true);
        if (mClockStyle == 2) {
            animateHide(mRightClock, animate, true);
        }
        animateHide(mSystemIconArea, animate, true);
        if (mShowLogo == 2) {
            animateHide(mDerpQuestLogoRight, animate, false);
        }
    }

    public void showSystemIconArea(boolean animate) {
        animateShow(mCenterClockLayout, animate);
        if (mClockStyle == 2) {
            animateShow(mRightClock, animate);
        }
        animateShow(mSystemIconArea, animate);
        if (mShowLogo == 2) {
            animateShow(mDerpQuestLogoRight, animate);
        }
    }

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate, true);
        animateHide(mCenteredIconArea, animate, true);
        animateHide(mCenterClockLayout, animate, true);
        if (mShowLogo == 1) {
            animateHide(mDerpQuestLogo, animate, false);
        }
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
        animateShow(mCenteredIconArea, animate);
        animateShow(mCenterClockLayout, animate);
        if (mShowLogo == 1) {
             animateShow(mDerpQuestLogo, animate);
        }
    }

    public void hideOperatorName(boolean animate) {
        if (mOperatorNameFrame != null) {
            animateHide(mOperatorNameFrame, animate, true);
        }
    }

    public void showOperatorName(boolean animate) {
        if (mOperatorNameFrame != null) {
            animateShow(mOperatorNameFrame, animate);
        }
    }

    public void hideCarrierName(boolean animate) {
        if (mCustomCarrierLabel != null) {
            animateHide(mCustomCarrierLabel, animate, mHasCarrierLabel);
        }
    }

    public void showCarrierName(boolean animate) {
        if (mCustomCarrierLabel != null) {
            setCarrierLabel(animate);
        }
    }

    public void hideSbWeather(boolean animate) {
        if (!mWeatherInHeaderView && mShowWeather != 0
        && mWeatherTextView != null && mWeatherImageView != null) {
            animateHide(mWeatherTextView, animate, false);
            animateHide(mWeatherImageView, animate, false);
    }
    }

    public void showSbWeather(boolean animate) {
        if (!mWeatherInHeaderView && mShowWeather != 0) {
        updateSBWeather(animate);
    }
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate, final boolean invisible) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(0f);
            v.setVisibility(invisible ? View.INVISIBLE : View.GONE);
            return;
        }

        v.animate()
                .alpha(0f)
                .setDuration(160)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(() -> v.setVisibility(invisible ? View.INVISIBLE : View.GONE));
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        if (v instanceof Clock && !((Clock)v).isClockVisible()) {
            return;
        }
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(FADE_IN_DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(FADE_IN_DELAY)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mKeyguardMonitor.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mKeyguardMonitor.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mKeyguardMonitor.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    private void initEmergencyCryptkeeperText() {
        View emergencyViewStub = mStatusBar.findViewById(R.id.emergency_cryptkeeper_text);
        if (mNetworkController.hasEmergencyCryptKeeperText()) {
            if (emergencyViewStub != null) {
                ((ViewStub) emergencyViewStub).inflate();
            }
            mNetworkController.addCallback(mSignalCallback);
        } else if (emergencyViewStub != null) {
            ViewGroup parent = (ViewGroup) emergencyViewStub.getParent();
            parent.removeView(emergencyViewStub);
        }
    }

    private void initOperatorName() {
        if (getResources().getBoolean(R.bool.config_showOperatorNameInStatusBar)) {
            ViewStub stub = mStatusBar.findViewById(R.id.operator_name);
            mOperatorNameFrame = stub.inflate();
        }
    }

    @Override
    public void onStateChanged(int newState) {

    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        disable(getContext().getDisplayId(), mDisabled1, mDisabled1, false /* animate */);
    }

    public void updateSettings(boolean animate) {
        if (mStatusBar == null) return;

        if (getContext() == null) {
            return;
        }

        try {
            mShowClock = Settings.System.getIntForUser(mContentResolver,
                    Settings.System.STATUS_BAR_CLOCK, 1,
                    UserHandle.USER_CURRENT) == 1;
            mHasCarrierLabel = (mShowCarrierLabel == 2 || mShowCarrierLabel == 3);
            if (!mShowClock) {
                mClockStyle = 1; // internally switch to centered clock layout because
                                // left & right will show up again after QS pulldown
            } else {
                mClockStyle = Settings.System.getIntForUser(mContentResolver,
                        Settings.System.STATUSBAR_CLOCK_STYLE, 0,
                        UserHandle.USER_CURRENT);
            }
            mShowCarrierLabel = Settings.System.getIntForUser(mContentResolver,
                    Settings.System.STATUS_BAR_SHOW_CARRIER, 0, UserHandle.USER_CURRENT);
            mTickerEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                    Settings.System.STATUS_BAR_SHOW_TICKER, 0, UserHandle.USER_CURRENT);
            mShowWeather = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0, UserHandle.USER_CURRENT);
            mWeatherInHeaderView = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION, 0, UserHandle.USER_CURRENT) == 1;
            updateClockStyle(animate);
            setCarrierLabel(animate);
            updateSBWeather(animate);
            initTickerView();
        } catch (Exception x) {
        }
    }

    public void updateLogoSettings(boolean animate) {
        Drawable logo = null;

        if (mStatusBar == null) return;

        if (getContext() == null) {
            return;
        }

        mShowLogo = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_LOGO, 0,
                UserHandle.USER_CURRENT);
        mLogoStyle = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_LOGO_STYLE, 0,
                UserHandle.USER_CURRENT);

        switch(mLogoStyle) {
                // DerpFest 1
            case 1:
                logo = getContext().getDrawable(R.drawable.ic_derpquest_logo);
                break;
                // DerpFest 2
            case 2:
                logo = getContext().getResources().getDrawable(R.drawable.ic_derpquest1_logo);
                break;
                // OWL
            case 3:
                logo = getContext().getResources().getDrawable(R.drawable.ic_owl_logo);
                break;
                // OWL 1
            case 4:
                logo = getContext().getResources().getDrawable(R.drawable.ic_owl1_logo);
                break;
                // Q
            case 5:
                logo = getContext().getResources().getDrawable(R.drawable.ic_q_logo);
                break;
                // OnePlus
            case 6:
                logo = getContext().getResources().getDrawable(R.drawable.ic_oneplus_logo);
                break;
                // Trident
            case 7:
                logo = getContext().getResources().getDrawable(R.drawable.ic_trident_logo);
                break;
                // Android
            case 8:
                logo = getContext().getResources().getDrawable(R.drawable.ic_android_logo);
                break;
                // BatMan
            case 9:
                logo = getContext().getDrawable(R.drawable.ic_batman_logo);
                break;
                // DeadPool
            case 10:
                logo = getContext().getResources().getDrawable(R.drawable.ic_deadpool_logo);
                break;
                // DecpetIcons
            case 11:
                logo = getContext().getResources().getDrawable(R.drawable.ic_decpeticons_logo);
                break;
                // IronMan
            case 12:
                logo = getContext().getResources().getDrawable(R.drawable.ic_ironman_logo);
                break;
                // Minions
            case 13:
                logo = getContext().getResources().getDrawable(R.drawable.ic_minions_logo);
                break;
                // SpiderMan
            case 14:
                logo = getContext().getResources().getDrawable(R.drawable.ic_spiderman_logo);
                break;
                // AEXLogo
            case 15:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_aex_logo);
                break;
                // AICPLogo
            case 16:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_aicp);
                break;
                // Alien
            case 17:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_alien);
                break;
                // AppLe
            case 18:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_apple_logo);
                break;
                // Beats
            case 19:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_beats);
                break;
                // Biohazard
            case 20:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_biohazard);
                break;
                // Blogger
            case 21:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_blogger);
                break;
                // Bomb
            case 22:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_bomb);
                break;
                // BootlegLogo
            case 23:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_bootleg);
                break;
                // Brain
            case 24:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_brain);
                break;
                // Clippy
            case 25:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_clippy);
                break;
                // Clown
            case 26:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_clown);
                break;
                // CosmisLogo
            case 27:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_cosmic);
                break;
                // CrDroidLogo
            case 28:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_crdroid);
                break;
                // DeathStar
            case 29:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_deathstar);
                break;
                // DerpAlt
            case 30:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_derp_alt);
                break;
                // DerpLogo
            case 31:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_derp_logo);
                break;
                // DiamondStone
            case 32:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_diamond_stone);
                break;
                // DramaMasks
            case 33:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_drama_masks);
                break;
                // DuLogo
            case 34:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_du);
                break;
                // EmoticonCool
            case 35:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_emoticon_cool_outline);
                break;
                // EmoticonDevil
            case 36:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_emoticon_devil);
                break;
                // FingerPrint
            case 37:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_fingerprint);
                break;
                // FootballHelmet
            case 38:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_football_helmet);
                break;
                // GamePad
            case 39:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_gamepad);
                break;
                // Ghost
            case 40:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_ghost);
                break;
                // GithubFace
            case 41:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_github_face);
                break;
                // GlassCocktail
            case 42:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_glass_cocktail);
                break;
                // GlassWine
            case 43:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_glass_wine);
                break;
                // Glitter
            case 44:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_glitter);
                break;
                // Graphql
            case 45:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_graphql);
                break;
                // GuitarElectric
            case 46:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_guitar_electric);
                break;
                // GuitarPick
            case 47:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_guitar_pick);
                break;
                // GZRLogo
            case 48:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_gzr);
                break;
                // GZRCircle
            case 49:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_gzr_circle);
                break;
                // GZRSkull
            case 50:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_gzr_skull);
                break;
                // HandOkay
            case 51:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_hand_okay);
                break;
                // HavocLogo
            case 52:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_havoc);
                break;
                // Heart
            case 53:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_heart);
                break;
                // Khloe
            case 54:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_khloe);
                break;
                // Kronic1
            case 55:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_kronic1);
                break;
                // Kronic2
            case 56:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_kronic2);
                break;
                // Kronic3
            case 57:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_kronic3);
                break;
                // KronicLogo
            case 58:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_kronic_logo);
                break;
                // Linux
            case 59:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_linux);
                break;
                // LiquidremixLogo
            case 60:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_liquid_remix);
                break;
                // MushRoom
            case 61:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_mushroom);
                break;
                // Nest
            case 62:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_nest);
                break;
                // NiceLogo
            case 63:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_nice_logo);
                break;
                // Ornament
            case 64:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_ornament);
                break;
                // Owl2
            case 65:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_owl2);
                break;
                // PacMan1
            case 66:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_pacman1);
                break;
                // PacMan2
            case 67:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_pacman2);
                break;
                // PineTree
            case 68:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_pine_tree);
                break;
                // ResuRectionRemixLogo
            case 69:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_rr);
                break;
                // WolfRunning
            case 70:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_running_wolf);
                break;
                // ShishuBuilds
            case 71:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_shishubuilds);
                break;
                // ShishuLogo
            case 72:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_shishu_logo);
                break;
                // SpaceInvaders
            case 73:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_space_invaders);
                break;
                // SungLasses
            case 74:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_sunglasses);
                break;
                // ThemeIcon01
            case 75:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_themeicon01);
                break;
                // ThemeIcon02
            case 76:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_themeicon02);
                break;
                // ThemeIcon03
            case 77:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_themeicon03);
                break;
                // TimerSand
            case 78:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_timer_sand);
                break;
                // TipsyBottle
            case 79:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_tipsy_bottle);
                break;
                // TipsyLetters
            case 80:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_tipsy_letters);
                break;
                // TipsyTavern
            case 81:
                logo = getContext().getDrawable(R.drawable.ic_status_bar_tipsy_tavern);
                break;
                // TipsyCheerz
            case 82:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_tipsy_cheerz);
                break;
                // ViperLoGo
            case 83:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_viper);
                break;
                // Wolf
            case 84:
                logo = getContext().getResources().getDrawable(R.drawable.ic_status_bar_wolf);
                break;
                // SuperMan
            case 85:
                logo = getContext().getResources().getDrawable(R.drawable.ic_superman_logo);
                break;
                // XtendedLogo
            case 86:
                logo = getContext().getResources().getDrawable(R.drawable.ic_xtnd_logo);
                break;
                // XtendedLogoShort
            case 87:
                logo = getContext().getResources().getDrawable(R.drawable.ic_xtnd_short);
                break;
                // YinYang
            case 88:
                logo = getContext().getResources().getDrawable(R.drawable.ic_yin_yang);
                break;
                // ZenXOSLogo
            case 89:
                logo = getContext().getDrawable(R.drawable.ic_zenx_logo);
                break;
                // DerpFest
            case 0:
            default:
                logo = getContext().getDrawable(R.drawable.status_bar_logo);
                break;
        }

        if (mShowLogo == 1) {
	          mDerpQuestLogo.setImageDrawable(null);
	          mDerpQuestLogo.setImageDrawable(logo);
	      } else if (mShowLogo == 2) {
	          mDerpQuestLogoRight.setImageDrawable(null);
	          mDerpQuestLogoRight.setImageDrawable(logo);
	      }

        if (mNotificationIconAreaInner != null) {
            if (mShowLogo == 1) {
                if (mNotificationIconAreaInner.getVisibility() == View.VISIBLE) {
                    animateShow(mDerpQuestLogo, animate);
                }
            } else if (mShowLogo != 1) {
                animateHide(mDerpQuestLogo, animate, false);
            }
        }

        if (mSystemIconArea != null) {
            if (mShowLogo == 2) {
                if (mSystemIconArea.getVisibility() == View.VISIBLE) {
                    animateShow(mDerpQuestLogoRight, animate);
                }
            } else if (mShowLogo != 2) {
                   animateHide(mDerpQuestLogoRight, animate, false);
            }
        }
    }

    private void updateClockStyle(boolean animate) {
        if (mClockStyle == 1 || mClockStyle == 2) {
            animateHide(mClockView, animate, false);
        } else {
            if (((Clock)mClockView).isClockVisible()) {
                animateShow(mClockView, animate);
            }
        }
    }

    private void setCarrierLabel(boolean animate) {
        if (mHasCarrierLabel) {
            animateShow(mCustomCarrierLabel, animate);
        } else {
            animateHide(mCustomCarrierLabel, animate, false);
        }
    }

    private void updateSBWeather(boolean animate) {
        if (!mWeatherInHeaderView && mShowWeather != 0) {
            if (mShowWeather == 1 || mShowWeather == 2
            || mShowWeather == 3 || mShowWeather == 4) {
                animateShow(mWeatherTextView, animate);
                animateShow(mWeatherImageView, animate);
            } else if (mShowWeather == 3 || mShowWeather == 4){
                animateShow(mWeatherTextView, animate);
                animateHide(mWeatherImageView, animate, false);
            } else if (mShowWeather == 5) {
                animateShow(mWeatherImageView, animate);
                animateHide(mWeatherTextView, animate, false);
            }
        }
    }

    private void initTickerView() {
        if (mTickerEnabled != 0) {
            View tickerStub = mStatusBar.findViewById(R.id.ticker_stub);
            if (mTickerViewFromStub == null && tickerStub != null) {
                mTickerViewFromStub = ((ViewStub) tickerStub).inflate();
            }
            TickerView tickerView = (TickerView) mStatusBar.findViewById(R.id.tickerText);
            ImageSwitcher tickerIcon = (ImageSwitcher) mStatusBar.findViewById(R.id.tickerIcon);
            mStatusBarComponent.createTicker(
                    mTickerEnabled, getContext(), mStatusBar, tickerView, tickerIcon, mTickerViewFromStub);
        } else {
            mStatusBarComponent.disableTicker();
        }
    }
}
