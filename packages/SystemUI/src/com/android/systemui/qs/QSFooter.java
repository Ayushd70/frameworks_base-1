/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.qs;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_DATE;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.CalendarContract.Events;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardStatusView;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.R.dimen;
import com.android.systemui.R.id;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.qs.TouchAnimator.Listener;
import com.android.systemui.qs.TouchAnimator.ListenerAdapter;
import com.android.systemui.statusbar.phone.ExpandableIndicator;
import com.android.systemui.statusbar.phone.MultiUserSwitch;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.EmergencyListener;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;

public class QSFooter extends FrameLayout implements
        NextAlarmChangeCallback, OnClickListener, OnLongClickListener, OnUserInfoChangedListener, EmergencyListener,
        SignalCallback {
    private static final float EXPAND_INDICATOR_THRESHOLD = .93f;

    private ActivityStarter mActivityStarter;
    private NextAlarmController mNextAlarmController;
    private UserInfoController mUserInfoController;
    private View mSettingsButton;

    private TextView mAlarmStatus;
    private View mAlarmStatusCollapsed;
    private View mDate;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mAlarmShowing;

    protected ExpandableIndicator mExpandIndicator;

    private boolean mListening;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private boolean mShowEmergencyCallsOnly;
    protected MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;
    private boolean mAlwaysShowMultiUserSwitch;

    protected TouchAnimator mSettingsAlpha;
    private float mExpansionAmount;

    protected View mEdit;
    private boolean mShowEditIcon;
    private TouchAnimator mAnimator;
    private View mDateTimeGroup;
    private boolean mKeyguardShowing;
    private TouchAnimator mAlarmAnimator;
    protected Vibrator mVibrator;

    public QSFooter(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Resources res = getResources();

        mShowEditIcon = res.getBoolean(R.bool.config_showQuickSettingsEditingIcon);

        mEdit = findViewById(android.R.id.edit);
        mEdit.setOnLongClickListener(this);
        mEdit.setVisibility(mShowEditIcon ? VISIBLE : GONE);

        if (mShowEditIcon) {
            findViewById(android.R.id.edit).setOnClickListener(view ->
                    Dependency.get(ActivityStarter.class).postQSRunnableDismissingKeyguard(() ->
                            mQsPanel.showEdit(view)));
        }

        mDateTimeGroup = findViewById(id.date_time_alarm_group);
        mDate = findViewById(R.id.date);

        mExpandIndicator = findViewById(R.id.expand_indicator);
        mExpandIndicator.setVisibility(
                res.getBoolean(R.bool.config_showQuickSettingsExpandIndicator)
                        ? VISIBLE : GONE);

        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mSettingsButton.setOnLongClickListener(this);

        mAlarmStatusCollapsed = findViewById(R.id.alarm_status_collapsed);
        mAlarmStatus = findViewById(R.id.alarm_status);
        mDateTimeGroup.setOnClickListener(this);
        mDateTimeGroup.setOnLongClickListener(this);

        mMultiUserSwitch = findViewById(R.id.multi_user_switch);
        mMultiUserSwitch.setOnLongClickListener(this);
        mMultiUserAvatar = mMultiUserSwitch.findViewById(R.id.multi_user_avatar);
        mAlwaysShowMultiUserSwitch = res.getBoolean(R.bool.config_alwaysShowMultiUserSwitcher);

        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);
        ((RippleDrawable) mExpandIndicator.getBackground()).setForceSoftware(true);

        updateResources();

        mNextAlarmController = Dependency.get(NextAlarmController.class);
        mUserInfoController = Dependency.get(UserInfoController.class);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> updateAnimator(right - left));
    }

    private void updateAnimator(int width) {
        int numTiles = QuickQSPanel.getNumQuickTiles(mContext);
        int size = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size)
                - mContext.getResources().getDimensionPixelSize(dimen.qs_quick_tile_padding);
        int remaining = (width - numTiles * size) / (numTiles - 1);
        int defSpace = mContext.getResources().getDimensionPixelOffset(R.dimen.default_gear_space);

        mAnimator = new Builder()
                .addFloat(mSettingsButton, "translationX", -(remaining - defSpace), 0)
                .addFloat(mSettingsButton, "rotation", -120, 0)
                .build();
        if (mAlarmShowing) {
            mAlarmAnimator = new Builder().addFloat(mDate, "alpha", 1, 0)
                    .addFloat(mDateTimeGroup, "translationX", 0, -mDate.getWidth())
                    .addFloat(mAlarmStatus, "alpha", 0, 1)
                    .setListener(new ListenerAdapter() {
                        @Override
                        public void onAnimationAtStart() {
                            mAlarmStatus.setVisibility(View.GONE);
                        }

                        @Override
                        public void onAnimationStarted() {
                            mAlarmStatus.setVisibility(View.VISIBLE);
                        }
                    }).build();
        } else {
            mAlarmAnimator = null;
            mAlarmStatus.setVisibility(View.GONE);
            mDate.setAlpha(1);
            mDateTimeGroup.setTranslationX(0);
        }
        setExpansion(mExpansionAmount);
    }

    public void vibrateFooter(int duration) {
        if (mVibrator != null) {
            if (mVibrator.hasVibrator()) { mVibrator.vibrate(duration); }
        }
   }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        FontSizeUtils.updateFontSize(mAlarmStatus, R.dimen.qs_date_collapsed_size);

        updateSettingsAnimator();
    }

    private void updateSettingsAnimator() {
        mSettingsAlpha = createSettingsAlphaAnimator();

        final boolean isRtl = isLayoutRtl();
        if (isRtl && mDate.getWidth() == 0) {
            mDate.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    mDate.setPivotX(getWidth());
                    mDate.removeOnLayoutChangeListener(this);
                }
            });
        } else {
            mDate.setPivotX(isRtl ? mDate.getWidth() : 0);
        }
    }

    @Nullable
    private TouchAnimator createSettingsAlphaAnimator() {
        // If the settings icon is not shown and the user switcher is always shown, then there
        // is nothing to animate.
        if (!mShowEditIcon && mAlwaysShowMultiUserSwitch) {
            return null;
        }

        TouchAnimator.Builder animatorBuilder = new TouchAnimator.Builder();
        animatorBuilder.setStartDelay(QSAnimator.EXPANDED_TILE_DELAY);

        if (mShowEditIcon) {
            animatorBuilder.addFloat(mEdit, "alpha", 0, 1);
        }

        if (!mAlwaysShowMultiUserSwitch) {
            animatorBuilder.addFloat(mMultiUserSwitch, "alpha", 0, 1);
        }

        return animatorBuilder.build();
    }

    public void setKeyguardShowing(boolean keyguardShowing) {
        mKeyguardShowing = keyguardShowing;
        setExpansion(mExpansionAmount);
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        if (nextAlarm != null) {
            String alarmString = KeyguardStatusView.formatNextAlarm(getContext(), nextAlarm);
            mAlarmStatus.setText(alarmString);
            mAlarmStatus.setContentDescription(mContext.getString(
                    R.string.accessibility_quick_settings_alarm, alarmString));
            mAlarmStatusCollapsed.setContentDescription(mContext.getString(
                    R.string.accessibility_quick_settings_alarm, alarmString));
        }
        if (mAlarmShowing != (nextAlarm != null)) {
            mAlarmShowing = nextAlarm != null;
            updateAnimator(getWidth());
            updateEverything();
        }
    }

    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mAnimator != null) mAnimator.setPosition(headerExpansionFraction);
        if (mAlarmAnimator != null) mAlarmAnimator.setPosition(
                mKeyguardShowing ? 0 : headerExpansionFraction);

        if (mSettingsAlpha != null) {
            mSettingsAlpha.setPosition(headerExpansionFraction);
        }

        updateAlarmVisibilities();

        mExpandIndicator.setExpanded(headerExpansionFraction > EXPAND_INDICATOR_THRESHOLD);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        super.onDetachedFromWindow();
    }

    private void updateAlarmVisibilities() {
        mAlarmStatusCollapsed.setVisibility(mAlarmShowing ? View.VISIBLE : View.GONE);
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        updateListeners();
    }

    public View getExpandView() {
        return findViewById(R.id.expand_indicator);
    }

    public void updateEverything() {
        post(() -> {
            updateVisibilities();
            setClickable(false);
        });
    }

    private void updateVisibilities() {
        updateAlarmVisibilities();
        mSettingsButton.setVisibility(View.VISIBLE);
        final boolean isDemo = UserManager.isDeviceInDemoMode(mContext);

        mMultiUserSwitch.setVisibility((mExpanded || mAlwaysShowMultiUserSwitch)
                && mMultiUserSwitch.hasMultipleUsers() && !isDemo
                ? View.VISIBLE : View.INVISIBLE);

        if (mShowEditIcon) {
            mEdit.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void updateListeners() {
        if (mListening) {
            mNextAlarmController.addCallback(this);
            mUserInfoController.addCallback(this);
            if (Dependency.get(NetworkController.class).hasVoiceCallingFeature()) {
                Dependency.get(NetworkController.class).addEmergencyListener(this);
                Dependency.get(NetworkController.class).addCallback(this);
            }
        } else {
            mNextAlarmController.removeCallback(this);
            mUserInfoController.removeCallback(this);
            Dependency.get(NetworkController.class).removeEmergencyListener(this);
            Dependency.get(NetworkController.class).removeCallback(this);
        }
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        if (mQsPanel != null) {
            mMultiUserSwitch.setQsPanel(qsPanel);
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            if (!Dependency.get(DeviceProvisionedController.class).isCurrentUserSetup()) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> { });
                return;
            }
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            startSettingsActivity();
        } else if (v == mDateTimeGroup) {
            Dependency.get(MetricsLogger.class).action(ACTION_QS_DATE,
                    mNextAlarm != null);
            if (mNextAlarm != null) {
                PendingIntent showIntent = mNextAlarm.getShowIntent();
                mActivityStarter.startPendingIntentDismissingKeyguard(showIntent);
            } else {
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mSettingsButton) {
            startSettingsLongClickActivity();
        } else if (v == mMultiUserSwitch) {
            startUserLongClickActivity();
        } else if (v == mDateTimeGroup) {
            startDateTimeLongClickActivity();
        } else if (v == mEdit) {
            startEditLongClickActivity();
        }
        vibrateFooter(20);
        return false;
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    private void startSettingsLongClickActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
	intent.setClassName("com.android.settings",
            "com.android.settings.Settings$CardinalSettingsActivity");
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private void startDateTimeLongClickActivity() {
        Intent intent = new Intent(Intent.ACTION_INSERT);
            intent.setData(Events.CONTENT_URI);
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private void startEditLongClickActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
	intent.setClassName("com.android.settings",
            "com.android.settings.Settings$QuickSettingsActivity");
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    private void startUserLongClickActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName("com.android.settings",
            "com.android.settings.Settings$UserSettingsActivity");
        mActivityStarter.startActivity(intent, true /* dismissShade */);
    }

    @Override
    public void setEmergencyCallsOnly(boolean show) {
        boolean changed = show != mShowEmergencyCallsOnly;
        if (changed) {
            mShowEmergencyCallsOnly = show;
            if (mExpanded) {
                updateEverything();
            }
        }
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        if (picture != null &&
                UserManager.get(mContext).isGuestUser(ActivityManager.getCurrentUser())) {
            picture = picture.getConstantState().newDrawable().mutate();
            picture.setColorFilter(
                    Utils.getColorAttr(mContext, android.R.attr.colorForeground),
                    Mode.SRC_IN);
        }
        mMultiUserAvatar.setImageDrawable(picture);
    }
}
