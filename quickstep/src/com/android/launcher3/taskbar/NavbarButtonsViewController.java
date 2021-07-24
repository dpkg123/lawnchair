/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.launcher3.taskbar;

import static com.android.launcher3.taskbar.TaskbarNavButtonController.BUTTON_A11Y;
import static com.android.launcher3.taskbar.TaskbarNavButtonController.BUTTON_A11Y_LONG_CLICK;
import static com.android.launcher3.taskbar.TaskbarNavButtonController.BUTTON_BACK;
import static com.android.launcher3.taskbar.TaskbarNavButtonController.BUTTON_HOME;
import static com.android.launcher3.taskbar.TaskbarNavButtonController.BUTTON_IME_SWITCH;
import static com.android.launcher3.taskbar.TaskbarNavButtonController.BUTTON_RECENTS;
import static com.android.launcher3.taskbar.TaskbarViewController.ALPHA_INDEX_IME;
import static com.android.launcher3.taskbar.TaskbarViewController.ALPHA_INDEX_KEYGUARD;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;

import android.animation.ObjectAnimator;
import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.util.Property;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnHoverListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.launcher3.LauncherAnimUtils;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AlphaUpdateListener;
import com.android.launcher3.taskbar.TaskbarNavButtonController.TaskbarButton;
import com.android.launcher3.taskbar.contextual.RotationButton;
import com.android.launcher3.taskbar.contextual.RotationButtonController;
import com.android.launcher3.util.MultiValueAlpha;
import com.android.quickstep.AnimatedFloat;

import java.util.ArrayList;
import java.util.function.IntPredicate;

/**
 * Controller for managing nav bar buttons in taskbar
 */
public class NavbarButtonsViewController {

    private final Rect mTempRect = new Rect();

    private static final int FLAG_SWITCHER_SUPPORTED = 1 << 0;
    private static final int FLAG_IME_VISIBLE = 1 << 1;
    private static final int FLAG_ROTATION_BUTTON_VISIBLE = 1 << 2;
    private static final int FLAG_A11Y_VISIBLE = 1 << 3;
    private static final int FLAG_ONLY_BACK_FOR_BOUNCER_VISIBLE = 1 << 4;
    private static final int FLAG_KEYGUARD_VISIBLE = 1 << 5;

    private static final int MASK_IME_SWITCHER_VISIBLE = FLAG_SWITCHER_SUPPORTED | FLAG_IME_VISIBLE;

    private View.OnLongClickListener mA11yLongClickListener;
    private final ArrayList<StatePropertyHolder> mPropertyHolders = new ArrayList<>();
    private final ArrayList<View> mAllButtons = new ArrayList<>();
    private int mState;

    private final TaskbarActivityContext mContext;
    private final FrameLayout mNavButtonsView;
    private final ViewGroup mNavButtonContainer;
    // Used for IME+A11Y buttons
    private final ViewGroup mEndContextualContainer;
    private final ViewGroup mStartContextualContainer;

    // Initialized in init.
    private TaskbarControllers mControllers;
    private View mA11yButton;
    private int mSysuiStateFlags;
    private View mBackButton;

    public NavbarButtonsViewController(TaskbarActivityContext context, FrameLayout navButtonsView) {
        mContext = context;
        mNavButtonsView = navButtonsView;
        mNavButtonContainer = mNavButtonsView.findViewById(R.id.end_nav_buttons);
        mEndContextualContainer = mNavButtonsView.findViewById(R.id.end_contextual_buttons);
        mStartContextualContainer = mNavButtonsView.findViewById(R.id.start_contextual_buttons);
    }

    /**
     * Initializes the controller
     */
    public void init(TaskbarControllers controllers) {
        mControllers = controllers;
        mNavButtonsView.getLayoutParams().height = mContext.getDeviceProfile().taskbarSize;

        mA11yLongClickListener = view -> {
            mControllers.navButtonController.onButtonClick(BUTTON_A11Y_LONG_CLICK);
            return true;
        };

        mPropertyHolders.add(new StatePropertyHolder(
                mControllers.taskbarViewController.getTaskbarIconAlpha()
                        .getProperty(ALPHA_INDEX_IME),
                flags -> (flags & FLAG_IME_VISIBLE) == 0, MultiValueAlpha.VALUE, 1, 0));

        // IME switcher
        View imeSwitcherButton = addButton(R.drawable.ic_ime_switcher, BUTTON_IME_SWITCH,
                mEndContextualContainer, mControllers.navButtonController, R.id.ime_switcher);
        mPropertyHolders.add(new StatePropertyHolder(imeSwitcherButton,
                flags -> ((flags & MASK_IME_SWITCHER_VISIBLE) == MASK_IME_SWITCHER_VISIBLE)
                        && ((flags & FLAG_ROTATION_BUTTON_VISIBLE) == 0)
                        && ((flags & FLAG_A11Y_VISIBLE) == 0)));

        View imeDownButton = addButton(R.drawable.ic_sysbar_back, BUTTON_BACK,
                mStartContextualContainer, mControllers.navButtonController, R.id.back);
        imeDownButton.setRotation(Utilities.isRtl(mContext.getResources()) ? 90 : -90);
        // Rotate when Ime visible
        mPropertyHolders.add(new StatePropertyHolder(imeDownButton,
                flags -> (flags & FLAG_IME_VISIBLE) != 0));

        mPropertyHolders.add(new StatePropertyHolder(
                mControllers.taskbarViewController.getTaskbarIconAlpha()
                        .getProperty(ALPHA_INDEX_KEYGUARD),
                flags -> (flags & FLAG_KEYGUARD_VISIBLE) == 0, MultiValueAlpha.VALUE, 1, 0));

        mPropertyHolders.add(new StatePropertyHolder(mControllers.taskbarDragLayerController
                .getKeyguardBgTaskbar(),
                flags -> (flags & FLAG_KEYGUARD_VISIBLE) == 0, AnimatedFloat.VALUE, 1, 0));

        if (mContext.isThreeButtonNav()) {
            initButtons(mNavButtonContainer, mEndContextualContainer,
                    mControllers.navButtonController);

            // Animate taskbar background when IME shows
            mPropertyHolders.add(new StatePropertyHolder(
                    mControllers.taskbarDragLayerController.getNavbarBackgroundAlpha(),
                    flags -> (flags & FLAG_IME_VISIBLE) != 0 ||
                            (flags & FLAG_ONLY_BACK_FOR_BOUNCER_VISIBLE) != 0,
                    AnimatedFloat.VALUE, 1, 0));

            // Rotation button
            RotationButton rotationButton = new RotationButtonImpl(
                    addButton(mEndContextualContainer, R.id.rotate_suggestion));
            rotationButton.hide();
            mControllers.rotationButtonController.setRotationButton(rotationButton);
        } else {
            mControllers.rotationButtonController.setRotationButton(new RotationButton() {});
        }

        applyState();
        mPropertyHolders.forEach(StatePropertyHolder::endAnimation);
    }

    private void initButtons(ViewGroup navContainer, ViewGroup endContainer,
            TaskbarNavButtonController navButtonController) {

        // Hide when keyguard is showing, show when bouncer is showing
        mPropertyHolders.add(new StatePropertyHolder(mBackButton,
                flags -> (flags & FLAG_KEYGUARD_VISIBLE) == 0 ||
                        (flags & FLAG_ONLY_BACK_FOR_BOUNCER_VISIBLE) != 0));

        mBackButton = addButton(R.drawable.ic_sysbar_back, BUTTON_BACK,
                mNavButtonContainer, mControllers.navButtonController, R.id.back);
        mPropertyHolders.add(new StatePropertyHolder(mBackButton,
                flags -> (flags & FLAG_IME_VISIBLE) == 0));

        // home and recents buttons
        View homeButton = addButton(R.drawable.ic_sysbar_home, BUTTON_HOME, navContainer,
                navButtonController, R.id.home);
        mPropertyHolders.add(new StatePropertyHolder(homeButton,
                flags -> (flags & FLAG_IME_VISIBLE) == 0 &&
                        (flags & FLAG_KEYGUARD_VISIBLE) == 0));
        View recentsButton = addButton(R.drawable.ic_sysbar_recent, BUTTON_RECENTS,
                navContainer, navButtonController, R.id.recent_apps);
        mPropertyHolders.add(new StatePropertyHolder(recentsButton,
                flags -> (flags & FLAG_IME_VISIBLE) == 0 &&
                        (flags & FLAG_KEYGUARD_VISIBLE) == 0));

        // A11y button
        mA11yButton = addButton(R.drawable.ic_sysbar_accessibility_button, BUTTON_A11Y,
                endContainer, navButtonController, R.id.accessibility_button);
        mPropertyHolders.add(new StatePropertyHolder(mA11yButton,
                flags -> (flags & FLAG_A11Y_VISIBLE) != 0
                        && (flags & FLAG_ROTATION_BUTTON_VISIBLE) == 0));
        mA11yButton.setOnLongClickListener(mA11yLongClickListener);
    }

    public void updateStateForSysuiFlags(int systemUiStateFlags, boolean forceUpdate) {
        boolean isImeVisible = (systemUiStateFlags & SYSUI_STATE_IME_SHOWING) != 0;
        boolean isImeSwitcherShowing = (systemUiStateFlags & SYSUI_STATE_IME_SWITCHER_SHOWING) != 0;
        boolean a11yVisible = (systemUiStateFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean a11yLongClickable =
                (systemUiStateFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        if (!forceUpdate && systemUiStateFlags == mSysuiStateFlags) {
            return;
        }
        mSysuiStateFlags = systemUiStateFlags;

        updateStateForFlag(FLAG_IME_VISIBLE, isImeVisible);
        updateStateForFlag(FLAG_SWITCHER_SUPPORTED, isImeSwitcherShowing);
        updateStateForFlag(FLAG_A11Y_VISIBLE, a11yVisible);
        if (mA11yButton != null) {
            // Only used in 3 button
            mA11yButton.setLongClickable(a11yLongClickable);
        }
        applyState();
    }

    /**
     * Should be called when we need to show back button for bouncer
     */
    public void setBackForBouncer(boolean isBouncerVisible) {
        updateStateForFlag(FLAG_ONLY_BACK_FOR_BOUNCER_VISIBLE, isBouncerVisible);
        applyState();
    }

    /**
     * Slightly misnamed, but should be called when keyguard OR AOD is showing
     */
    public void setKeyguardVisible(boolean isKeyguardVisible) {
        updateStateForFlag(FLAG_KEYGUARD_VISIBLE, isKeyguardVisible);
        applyState();
    }

    /**
     * Returns true if IME bar is visible
     */
    public boolean isImeVisible() {
        return (mState & FLAG_IME_VISIBLE) != 0;
    }

    /**
     * Adds the bounds corresponding to all visible buttons to provided region
     */
    public void addVisibleButtonsRegion(TaskbarDragLayer parent, Region outRegion) {
        int count = mAllButtons.size();
        for (int i = 0; i < count; i++) {
            View button = mAllButtons.get(i);
            if (button.getVisibility() == View.VISIBLE) {
                parent.getDescendantRectRelativeToSelf(button, mTempRect);
                outRegion.op(mTempRect, Op.UNION);
            }
        }
    }

    /**
     * Does not call {@link #applyState()}. Don't forget to!
     */
    private void updateStateForFlag(int flag, boolean enabled) {
        if (enabled) {
            mState |= flag;
        } else {
            mState &= ~flag;
        }
    }

    private void applyState() {
        int count = mPropertyHolders.size();
        for (int i = 0; i < count; i++) {
            mPropertyHolders.get(i).setState(mState);
        }
    }

    private ImageView addButton(@DrawableRes int drawableId, @TaskbarButton int buttonType,
            ViewGroup parent, TaskbarNavButtonController navButtonController, @IdRes int id) {
        ImageView buttonView = addButton(parent, id);
        buttonView.setImageResource(drawableId);
        buttonView.setOnClickListener(view -> navButtonController.onButtonClick(buttonType));
        return buttonView;
    }

    private ImageView addButton(ViewGroup parent, int id) {
        ImageView buttonView = (ImageView) mContext.getLayoutInflater()
                .inflate(R.layout.taskbar_nav_button, parent, false);
        buttonView.setId(id);
        parent.addView(buttonView);
        mAllButtons.add(buttonView);
        return buttonView;
    }

    private class RotationButtonImpl implements RotationButton {

        private final ImageView mButton;
        private AnimatedVectorDrawable mImageDrawable;

        RotationButtonImpl(ImageView button) {
            mButton = button;
        }

        @Override
        public void setRotationButtonController(RotationButtonController rotationButtonController) {
            // TODO(b/187754252) UI polish, different icons based on light/dark context, etc
            mImageDrawable = (AnimatedVectorDrawable) mButton.getContext()
                    .getDrawable(rotationButtonController.getIconResId());
            mButton.setImageDrawable(mImageDrawable);
            mImageDrawable.setCallback(mButton);
        }

        @Override
        public View getCurrentView() {
            return mButton;
        }

        @Override
        public void show() {
            mButton.setVisibility(View.VISIBLE);
            mState |= FLAG_ROTATION_BUTTON_VISIBLE;
            applyState();
        }

        @Override
        public void hide() {
            mButton.setVisibility(View.GONE);
            mState &= ~FLAG_ROTATION_BUTTON_VISIBLE;
            applyState();
        }

        @Override
        public boolean isVisible() {
            return mButton.getVisibility() == View.VISIBLE;
        }

        @Override
        public void updateIcon(int lightIconColor, int darkIconColor) {
            // TODO(b/187754252): UI Polish
        }

        @Override
        public void setOnClickListener(OnClickListener onClickListener) {
            mButton.setOnClickListener(onClickListener);
        }

        @Override
        public void setOnHoverListener(OnHoverListener onHoverListener) {
            mButton.setOnHoverListener(onHoverListener);
        }

        @Override
        public AnimatedVectorDrawable getImageDrawable() {
            return mImageDrawable;
        }

        @Override
        public void setDarkIntensity(float darkIntensity) {
            // TODO(b/187754252) UI polish
        }

        @Override
        public boolean acceptRotationProposal() {
            return mButton.isAttachedToWindow();
        }
    }

    private static class StatePropertyHolder {

        private final float mEnabledValue, mDisabledValue;
        private final ObjectAnimator mAnimator;
        private final IntPredicate mEnableCondition;

        private boolean mIsEnabled = true;

        StatePropertyHolder(View view, IntPredicate enableCondition) {
            this(view, enableCondition, LauncherAnimUtils.VIEW_ALPHA, 1, 0);
            mAnimator.addListener(new AlphaUpdateListener(view));
        }

        <T> StatePropertyHolder(T target, IntPredicate enabledCondition,
                Property<T, Float> property, float enabledValue, float disabledValue) {
            mEnableCondition = enabledCondition;
            mEnabledValue = enabledValue;
            mDisabledValue = disabledValue;
            mAnimator = ObjectAnimator.ofFloat(target, property, enabledValue, disabledValue);
        }

        public void setState(int flags) {
            boolean isEnabled = mEnableCondition.test(flags);
            if (mIsEnabled != isEnabled) {
                mIsEnabled = isEnabled;
                mAnimator.cancel();
                mAnimator.setFloatValues(mIsEnabled ? mEnabledValue : mDisabledValue);
                mAnimator.start();
            }
        }

        public void endAnimation() {
            if (mAnimator.isRunning()) {
                mAnimator.end();
            }
        }
    }
}
