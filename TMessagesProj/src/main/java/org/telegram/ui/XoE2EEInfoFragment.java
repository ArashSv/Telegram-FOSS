package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.rest.e2ee.XoE2EE;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.BulletinFactory;

/**
 * T71 — the encryption / safety-number verification screen (docs/E2EE.md §trust).
 *
 * <p>Shows the 60-digit safety number derived from BOTH identity keys on
 * the two devices (never from the server), the verification state, and the
 * hard security warning when a peer identity key CHANGED (the malicious-
 * server key-substitution tripwire): while flagged, 1:1 sending is blocked
 * client-side and the chat must be re-verified + the session reset before
 * traffic resumes.
 */
public class XoE2EEInfoFragment extends BaseFragment {

    private final long peerUserId;
    private TextView statusView;
    private TextView warningView;
    private TextView safetyNumberView;
    private TextView badgeView;
    private TextView verifyButton;
    private TextView unverifyButton;
    private TextView resetButton;

    public XoE2EEInfoFragment(long peerUserId) {
        this.peerUserId = peerUserId;
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.XoE2eeTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        int account = currentAccount;
        long selfId = UserConfig.getInstance(account).clientUserId;
        XoE2EE e2ee = XoE2EE.getInstance(account);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        FrameLayout frameLayout = new FrameLayout(context);
        scrollView.addView(frameLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        frameLayout.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // status
        statusView = new TextView(context);
        statusView.setTextSize(15);
        statusView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        statusView.setGravity(Gravity.START);
        statusView.setText(LocaleController.getString(R.string.XoE2eeActive));
        layout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // security warning (identity change)
        warningView = new TextView(context);
        warningView.setTextSize(15);
        warningView.setTypeface(Typeface.DEFAULT_BOLD);
        warningView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        warningView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        warningView.setGravity(Gravity.START);
        layout.addView(warningView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // safety number header
        TextView header = new TextView(context);
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        header.setText(LocaleController.getString(R.string.XoE2eeSafetyNumberHeader));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 6));

        // safety number
        safetyNumberView = new TextView(context);
        safetyNumberView.setTextSize(22);
        safetyNumberView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        safetyNumberView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (Build.VERSION.SDK_INT >= 21) {
            safetyNumberView.setLetterSpacing(0.15f); // API 21+; minSdk 19 must not crash
        }
        safetyNumberView.setLineSpacing(AndroidUtilities.dp(6), 1f);
        safetyNumberView.setGravity(Gravity.CENTER);
        safetyNumberView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        safetyNumberView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        layout.addView(safetyNumberView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // verification badge
        badgeView = new TextView(context);
        badgeView.setTextSize(14);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        badgeView.setGravity(Gravity.CENTER);
        layout.addView(badgeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // buttons
        verifyButton = button(context, LocaleController.getString(R.string.XoE2eeMarkVerified), Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), () -> {
            XoE2EE.getInstance(currentAccount).markVerified(
                    UserConfig.getInstance(currentAccount).clientUserId, peerUserId, currentSafetyNumber());
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.XoE2eeVerifiedBadge)).show();
            refresh();
        });
        layout.addView(verifyButton);

        unverifyButton = button(context, LocaleController.getString(R.string.XoE2eeUnverify), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6), () -> {
            XoE2EE.getInstance(currentAccount).unverify(peerUserId);
            refresh();
        });
        layout.addView(unverifyButton);

        resetButton = button(context, LocaleController.getString(R.string.XoE2eeResetSession), Theme.getColor(Theme.key_text_RedBold), () -> {
            XoE2EE.getInstance(currentAccount).resetSession(peerUserId);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.XoE2eeTitle)).show();
            refresh();
        });
        layout.addView(resetButton);

        TextView hint = new TextView(context);
        hint.setTextSize(13);
        hint.setLineSpacing(AndroidUtilities.dp(2), 1f);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        hint.setText(LocaleController.getString(R.string.XoE2eeSafetyNumberHint));
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        // seed + render
        safetyNumber = e2ee.safetyNumber(selfId, peerUserId);
        refresh();
        fragmentView = scrollView;
        return fragmentView;
    }

    private String safetyNumber;

    private String currentSafetyNumber() {
        return safetyNumber;
    }

    private TextView button(Context context, String text, int color, Runnable onClick) {
        TextView view = new TextView(context);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14));
        view.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        view.setText(text);
        view.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        params.bottomMargin = AndroidUtilities.dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private void refresh() {
        int account = currentAccount;
        long selfId = UserConfig.getInstance(account).clientUserId;
        XoE2EE e2ee = XoE2EE.getInstance(account);

        boolean flagged = e2ee.isFlagged(peerUserId);
        boolean verified = e2ee.isVerified(peerUserId);
        String number = safetyNumber;

        warningView.setVisibility(flagged ? View.VISIBLE : View.GONE);
        if (flagged) {
            warningView.setText(LocaleController.getString(R.string.XoE2eeIdentityChanged));
            warningView.setTextColor(0xFFFFFFFF);
            warningView.setBackgroundColor(0xFFE53935);
        }
        resetButton.setVisibility(flagged ? View.VISIBLE : View.GONE);

        if (number != null && number.length() >= 48) {
            // 60 digits -> 6 groups of 5, two per line for readability
            StringBuilder pretty = new StringBuilder();
            for (int a = 0; a < number.length(); a += 5) {
                if (a > 0) {
                    pretty.append(a % 30 == 0 ? "\n" : " ");
                }
                pretty.append(number, a, Math.min(a + 5, number.length()));
            }
            safetyNumberView.setText(pretty.toString());
        } else {
            safetyNumberView.setText(LocaleController.getString(R.string.XoE2eeNotReady));
        }

        badgeView.setText(verified
                ? LocaleController.getString(R.string.XoE2eeVerifiedBadge)
                : LocaleController.getString(R.string.XoE2eeNotVerifiedBadge));
        badgeView.setTextColor(verified ? 0xFF43A047 : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));

        verifyButton.setVisibility(flagged || verified ? View.GONE : View.VISIBLE);
        unverifyButton.setVisibility(verified && !flagged ? View.VISIBLE : View.GONE);

        TLRPC.User peer = getMessagesController().getUser(peerUserId);
        if (peer != null) {
            actionBar.setTitle(peer.first_name + " — " + LocaleController.getString(R.string.XoE2eeTitle));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }
}
