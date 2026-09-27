package org.telegram.ui;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.RestAuthController;
import org.telegram.tgnet.rest.RestGateway;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.SimpleThemeDescription;
import org.telegram.ui.Components.TransformableLoginButtonView;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import android.graphics.Outline;

/**
 * T50 — change the two-step-verification password from the privacy section,
 * against the REST backend (the MTProto TwoStepVerificationActivity pair is
 * dead in this fork and is no longer opened from here).
 *
 * Wizard: current password (auth/verify-password.php) → new password →
 * confirm → auth/change-password.php. The server bumps the session era and
 * revokes every other device; the fresh pair returned for THIS device is
 * persisted by RestGateway. Telegram's own visual shell (lock lottie +
 * outline fields + floating confirm button) is preserved.
 */
public class XoPasswordChangeFragment extends BaseFragment {

    private static final int STAGE_CURRENT = 0;
    private static final int STAGE_NEW = 1;
    private static final int STAGE_CONFIRM = 2;

    private TextView titleView;
    private OutlineTextContainerView outlineField;
    private android.widget.EditText field;
    private TextView errorTextView;
    private RLottieImageView lockImageView;
    private FrameLayout floatingButtonContainer;
    private TransformableLoginButtonView floatingButtonIcon;

    private int stage = STAGE_CURRENT;
    private String currentPassword;
    private String newPassword;
    private boolean nextPressed;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.TwoStepVerification));
        actionBar.setAllowOverlayTitle(true);

        fragmentView = new ScrollView(context);
        ScrollView scrollView = (ScrollView) fragmentView;
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout lockFrameLayout = new FrameLayout(context);
        lockImageView = new RLottieImageView(context);
        lockImageView.setAnimation(R.raw.tsv_setup_intro, 120, 120);
        lockImageView.setAutoRepeat(false);
        lockFrameLayout.addView(lockImageView, LayoutHelper.createFrame(120, 120, Gravity.CENTER_HORIZONTAL));
        lockFrameLayout.setVisibility(AndroidUtilities.isSmallScreen() || (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y && !AndroidUtilities.isTablet()) ? View.GONE : View.VISIBLE);
        content.addView(lockFrameLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(getString(R.string.XoCurrentPasswordTitle));
        titleView.setGravity(Gravity.CENTER);
        titleView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 16, 32, 0));

        outlineField = new OutlineTextContainerView(context);
        outlineField.setText(getString(R.string.XoPasswordCurrent));
        final android.widget.EditText fieldView = new android.widget.EditText(context);
        field = fieldView;
        field.setCursorVisible(true);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setMaxLines(1);
        int padding = AndroidUtilities.dp(16);
        field.setPadding(padding, padding, padding, padding);
        field.setBackground(null);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setTypeface(Typeface.DEFAULT);
        field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnFocusChangeListener((v, hasFocus) -> outlineField.animateSelection(hasFocus ? 1f : 0f));
        outlineField.attachEditText(fieldView);
        outlineField.addView(fieldView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        field.setOnEditorActionListener((textView, i, keyEvent) -> {
            if (i == EditorInfo.IME_ACTION_DONE) {
                onNextPressed();
                return true;
            }
            return false;
        });
        content.addView(outlineField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 24, 16, 0));

        errorTextView = new TextView(context);
        errorTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        errorTextView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        errorTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        errorTextView.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), 0);
        errorTextView.setVisibility(View.GONE);
        content.addView(errorTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

        // Floating confirm button — the same shell LoginActivity uses.
        floatingButtonContainer = new FrameLayout(context);
        floatingButtonContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, AndroidUtilities.dp(56), AndroidUtilities.dp(56));
            }
        });
        floatingButtonContainer.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(56),
                Theme.getColor(Theme.key_chats_actionBackground), Theme.getColor(Theme.key_chats_actionPressedBackground)));
        floatingButtonIcon = new TransformableLoginButtonView(context);
        floatingButtonIcon.setTransformType(TransformableLoginButtonView.TRANSFORM_OPEN_ARROW);
        floatingButtonIcon.setProgress(1f);
        floatingButtonIcon.setDrawBackground(false);
        floatingButtonContainer.addView(floatingButtonIcon, LayoutHelper.createFrame(Build.VERSION.SDK_INT >= 21 ? 56 : 60, Build.VERSION.SDK_INT >= 21 ? 56 : 60));
        floatingButtonContainer.setContentDescription(getString("Done", R.string.Done));
        floatingButtonContainer.setOnClickListener(v -> onNextPressed());
        FrameLayout parent = new FrameLayout(context);
        parent.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(floatingButtonContainer, LayoutHelper.createFrame(Build.VERSION.SDK_INT >= 21 ? 56 : 60, Build.VERSION.SDK_INT >= 21 ? 56 : 60, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 16, 16));
        fragmentView = parent;

        applyStage(STAGE_CURRENT);
        return fragmentView;
    }

    private void applyStage(int newStage) {
        stage = newStage;
        field.setText("");
        errorTextView.setVisibility(View.GONE);
        if (stage == STAGE_CURRENT) {
            titleView.setText(getString(R.string.XoCurrentPasswordTitle));
            outlineField.setText(getString(R.string.XoPasswordCurrent));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        } else if (stage == STAGE_NEW) {
            titleView.setText(getString(R.string.XoNewPasswordTitle));
            outlineField.setText(getString(R.string.XoSetupFieldPassword));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        } else {
            titleView.setText(getString(R.string.XoSetupConfirmTitle));
            outlineField.setText(getString(R.string.XoSetupFieldConfirm));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        }
        field.requestFocus();
        field.setSelection(field.length());
        AndroidUtilities.showKeyboard(field);
    }

    private void showError(String text) {
        errorTextView.setText(text);
        errorTextView.setVisibility(View.VISIBLE);
        outlineField.animateSelection(1f);
        try {
            Vibrator vibrator = (Vibrator) getParentActivity().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
        }
    }

    private void onNextPressed() {
        if (getParentActivity() == null || nextPressed) {
            return;
        }
        String value = field.getText().toString();
        if (stage == STAGE_CURRENT) {
            if (value.length() == 0) {
                showError(getString(R.string.XoWrongPassword));
                return;
            }
            nextPressed = true;
            errorTextView.setVisibility(View.GONE);
            RestAuthController.verifyPassword(currentAccount, value, new RestAuthController.Callback<Boolean>() {
                @Override
                public void onResult(Boolean ok) {
                    nextPressed = false;
                    currentPassword = value;
                    applyStage(STAGE_NEW);
                }

                @Override
                public void onError(TLRPC.TL_error error) {
                    nextPressed = false;
                    if (error.text != null && error.text.equals("PASSWORD_INVALID")) {
                        showError(getString(R.string.XoWrongPassword));
                    } else if (error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                        showError(getString(R.string.XoTooManyAttempts));
                    } else {
                        showError(getString(R.string.XoPasswordError));
                    }
                }
            });
            return;
        }
        if (stage == STAGE_NEW) {
            if (value.length() < 4 || value.length() > 64) {
                showError(getString(R.string.XoPasswordTooShort));
                return;
            }
            newPassword = value;
            applyStage(STAGE_CONFIRM);
            return;
        }
        if (!newPassword.equals(value)) {
            newPassword = null;
            applyStage(STAGE_NEW);
            showError(getString(R.string.XoPasswordsMismatch));
            return;
        }
        nextPressed = true;
        errorTextView.setVisibility(View.GONE);
        AndroidUtilities.hideKeyboard(field);
        // The stored hint is kept: the request omits the hint key on purpose.
        RestAuthController.changePassword(currentAccount, currentPassword, newPassword, null, new RestAuthController.Callback<RestGateway.VerifyResult>() {
            @Override
            public void onResult(RestGateway.VerifyResult result) {
                nextPressed = false;
                if (getParentActivity() == null) {
                    return;
                }
                BulletinFactory.of(XoPasswordChangeFragment.this)
                        .createSimpleBulletin(R.raw.contact_check, getString(R.string.XoPasswordSaved))
                        .show();
                finishFragment();
            }

            @Override
            public void onError(TLRPC.TL_error error) {
                nextPressed = false;
                if (error.text != null && error.text.equals("PASSWORD_INVALID")) {
                    showError(getString(R.string.XoWrongPassword));
                    applyStage(STAGE_CURRENT);
                } else if (error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                    showError(getString(R.string.XoTooManyAttempts));
                } else {
                    showError(getString(R.string.XoPasswordError));
                }
            }
        });
    }

    private boolean handleBack() {
        if (nextPressed) {
            return false;
        }
        if (stage > STAGE_CURRENT) {
            applyStage(stage - 1);
            return false;
        }
        return true;
    }

    @Override
    public boolean onBackPressed() {
        return handleBack();
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return SimpleThemeDescription.createThemeDescriptions(this::updateColors,
                Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteGrayText6,
                Theme.key_text_RedRegular, Theme.key_chats_actionBackground, Theme.key_chats_actionIcon);
    }

    private void updateColors() {
        if (fragmentView == null) {
            return;
        }
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        errorTextView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        outlineField.updateColor();
    }
}
