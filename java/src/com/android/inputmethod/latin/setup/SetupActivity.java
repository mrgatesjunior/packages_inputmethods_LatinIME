/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.inputmethod.latin.setup;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Message;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import com.android.inputmethod.compat.TextViewCompatUtils;
import com.android.inputmethod.compat.ViewCompatUtils;
import com.android.inputmethod.latin.CollectionUtils;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.RichInputMethodManager;
import com.android.inputmethod.latin.SettingsActivity;
import com.android.inputmethod.latin.StaticInnerHandlerWrapper;

import java.util.HashMap;

public final class SetupActivity extends Activity {
    private SetupStepIndicatorView mStepIndicatorView;
    private final SetupStepGroup mSetupSteps = new SetupStepGroup();
    private static final String STATE_STEP = "step";
    private int mStepNo;
    private static final int STEP_1 = 1;
    private static final int STEP_2 = 2;
    private static final int STEP_3 = 3;

    private final SettingsPoolingHandler mHandler = new SettingsPoolingHandler(this);

    static final class SettingsPoolingHandler extends StaticInnerHandlerWrapper<SetupActivity> {
        private static final int MSG_POLLING_IME_SETTINGS = 0;
        private static final long IME_SETTINGS_POLLING_INTERVAL = 200;

        public SettingsPoolingHandler(final SetupActivity outerInstance) {
            super(outerInstance);
        }

        @Override
        public void handleMessage(final Message msg) {
            final SetupActivity setupActivity = getOuterInstance();
            switch (msg.what) {
            case MSG_POLLING_IME_SETTINGS:
                if (setupActivity.isMyImeEnabled()) {
                    setupActivity.invokeSetupWizardOfThisIme();
                    return;
                }
                startPollingImeSettings();
                break;
            }
        }

        public void startPollingImeSettings() {
            sendMessageDelayed(obtainMessage(MSG_POLLING_IME_SETTINGS),
                    IME_SETTINGS_POLLING_INTERVAL);
        }

        public void cancelPollingImeSettings() {
            removeMessages(MSG_POLLING_IME_SETTINGS);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.setup_wizard);

        RichInputMethodManager.init(this);

        if (savedInstanceState == null) {
            mStepNo = determineSetupStepNo();
        } else {
            mStepNo = savedInstanceState.getInt(STATE_STEP);
        }

        if (mStepNo == STEP_3) {
            // This IME already has been enabled and set as current IME.
            // TODO: Implement tutorial.
            invokeSettingsOfThisIme();
            finish();
            return;
        }

        // TODO: Use sans-serif-thin font family depending on the system locale white list and
        // the SDK version.
        final TextView titleView = (TextView)findViewById(R.id.setup_title);
        titleView.setText(getString(R.string.setup_title, getString(R.string.english_ime_name)));

        mStepIndicatorView = (SetupStepIndicatorView)findViewById(R.id.setup_step_indicator);

        final SetupStep step1 = new SetupStep(findViewById(R.id.setup_step1),
                R.string.setup_step1_title, R.string.setup_step1_instruction,
                R.drawable.ic_settings_language, R.string.language_settings);
        step1.setAction(new Runnable() {
            @Override
            public void run() {
                invokeLanguageAndInputSettings();
                mHandler.startPollingImeSettings();
            }
        });
        mSetupSteps.addStep(STEP_1, step1);

        final SetupStep step2 = new SetupStep(findViewById(R.id.setup_step2),
                R.string.setup_step2_title, R.string.setup_step2_instruction,
                0 /* actionIcon */, R.string.select_input_method);
        step2.setAction(new Runnable() {
            @Override
            public void run() {
                // Invoke input method picker.
                RichInputMethodManager.getInstance().getInputMethodManager()
                        .showInputMethodPicker();
            }
        });
        mSetupSteps.addStep(STEP_2, step2);

        final SetupStep step3 = new SetupStep(findViewById(R.id.setup_step3),
                R.string.setup_step3_title, 0 /* instruction */,
                R.drawable.sym_keyboard_language_switch, R.string.setup_step3_instruction);
        step3.setAction(new Runnable() {
            @Override
            public void run() {
                invokeSubtypeEnablerOfThisIme();
            }
        });
        mSetupSteps.addStep(STEP_3, step3);
    }

    private void invokeSetupWizardOfThisIme() {
        final Intent intent = new Intent();
        intent.setClass(this, SetupActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void invokeSettingsOfThisIme() {
        final Intent intent = new Intent();
        intent.setClass(this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void invokeLanguageAndInputSettings() {
        final Intent intent = new Intent();
        intent.setAction(Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        startActivity(intent);
    }

    private void invokeSubtypeEnablerOfThisIme() {
        final InputMethodInfo imi =
                RichInputMethodManager.getInstance().getInputMethodInfoOfThisIme();
        final Intent intent = new Intent();
        intent.setAction(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.putExtra(Settings.EXTRA_INPUT_METHOD_ID, imi.getId());
        startActivity(intent);
    }

    private boolean isMyImeEnabled() {
        final String packageName = getPackageName();
        final InputMethodManager imm = RichInputMethodManager.getInstance().getInputMethodManager();
        for (final InputMethodInfo imi : imm.getEnabledInputMethodList()) {
            if (packageName.equals(imi.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isMyImeCurrent() {
        final InputMethodInfo myImi =
                RichInputMethodManager.getInstance().getInputMethodInfoOfThisIme();
        final String currentImeId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return myImi.getId().equals(currentImeId);
    }

    private int determineSetupStepNo() {
        mHandler.cancelPollingImeSettings();
        if (!isMyImeEnabled()) {
            return STEP_1;
        }
        if (!isMyImeCurrent()) {
            return STEP_2;
        }
        return STEP_3;
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_STEP, mStepNo);
    }

    @Override
    protected void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mStepNo = savedInstanceState.getInt(STATE_STEP);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStepNo = determineSetupStepNo();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        mStepNo = determineSetupStepNo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSetupStepView();
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            return;
        }
        mStepNo = determineSetupStepNo();
        updateSetupStepView();
    }

    private void updateSetupStepView() {
        final int layoutDirection = ViewCompatUtils.getLayoutDirection(mStepIndicatorView);
        mStepIndicatorView.setIndicatorPosition(
                getIndicatorPosition(mStepNo, mSetupSteps.getTotalStep(), layoutDirection));
        mSetupSteps.enableStep(mStepNo);
    }

    private static float getIndicatorPosition(final int step, final int totalStep,
            final int layoutDirection) {
        final float pos = ((step - STEP_1) * 2 + 1) / (float)(totalStep * 2);
        return (layoutDirection == ViewCompatUtils.LAYOUT_DIRECTION_RTL) ? 1.0f - pos : pos;
    }

    static final class SetupStep implements View.OnClickListener {
        private final View mRootView;
        private final TextView mActionLabel;
        private Runnable mAction;

        public SetupStep(final View rootView, final int title, final int instruction,
                final int actionIcon, final int actionLabel) {
            mRootView = rootView;
            final Resources res = rootView.getResources();
            final String applicationName = res.getString(R.string.english_ime_name);

            final TextView titleView = (TextView)rootView.findViewById(R.id.setup_step_title);
            titleView.setText(res.getString(title, applicationName));

            final TextView instructionView = (TextView)rootView.findViewById(
                    R.id.setup_step_instruction);
            if (instruction == 0) {
                instructionView.setVisibility(View.GONE);
            } else {
                instructionView.setText(res.getString(instruction, applicationName));
            }

            mActionLabel = (TextView)rootView.findViewById(R.id.setup_step_action_label);
            mActionLabel.setText(res.getString(actionLabel));
            if (actionIcon == 0) {
                final int paddingEnd = ViewCompatUtils.getPaddingEnd(mActionLabel);
                ViewCompatUtils.setPaddingRelative(mActionLabel, paddingEnd, 0, paddingEnd, 0);
            } else {
                final int overrideColor = res.getColor(R.color.setup_text_action);
                final Drawable icon = res.getDrawable(actionIcon);
                icon.setColorFilter(overrideColor, PorterDuff.Mode.MULTIPLY);
                icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
                TextViewCompatUtils.setCompoundDrawablesRelative(
                        mActionLabel, icon, null, null, null);
            }
        }

        public void setEnabled(final boolean enabled) {
            mRootView.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }

        public void setAction(final Runnable action) {
            mActionLabel.setOnClickListener(this);
            mAction = action;
        }

        @Override
        public void onClick(final View v) {
            if (mAction != null) {
                mAction.run();
            }
        }
    }

    static final class SetupStepGroup {
        private final HashMap<Integer, SetupStep> mGroup = CollectionUtils.newHashMap();

        public void addStep(final int stepNo, final SetupStep step) {
            mGroup.put(stepNo, step);
        }

        public void enableStep(final int enableStepNo) {
            for (final Integer stepNo : mGroup.keySet()) {
                final SetupStep step = mGroup.get(stepNo);
                step.setEnabled(stepNo == enableStepNo);
            }
        }

        public int getTotalStep() {
            return mGroup.size();
        }
    }
}