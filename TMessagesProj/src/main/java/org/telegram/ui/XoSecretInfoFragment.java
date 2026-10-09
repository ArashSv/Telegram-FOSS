package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.rest.e2ee.XoSecret;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * T78 — the SECRET-chat encryption screen.
 *
 * <p>Shows the stateless-ECIES model in user terms: one key pair per user,
 * the server never sees a private key, and the safety fingerprint derived
 * from BOTH public keys (identical on both devices — compare out of band to
 * rule out a key substitution). A key change (peer reinstalled) keeps old
 * traffic readable by design but is surfaced loudly here.
 */
public class XoSecretInfoFragment extends BaseFragment {

    private final long secretChatId;
    private final long peerUserId;
    private TextView statusView;
    private TextView warningView;
    private TextView fingerprintView;
    private TextView hintView;

    public XoSecretInfoFragment(long secretChatId, long peerUserId) {
        this.secretChatId = secretChatId;
        this.peerUserId = peerUserId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.XoSecretTitle));
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
        XoSecret secret = XoSecret.getInstance(account);

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
        statusView.setText(LocaleController.getString(R.string.XoSecretActive));
        layout.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // key-change warning
        warningView = new TextView(context);
        warningView.setTextSize(15);
        warningView.setTypeface(Typeface.DEFAULT_BOLD);
        warningView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        warningView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        warningView.setGravity(Gravity.START);
        warningView.setVisibility(View.GONE);
        layout.addView(warningView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // fingerprint header
        TextView header = new TextView(context);
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        header.setText(LocaleController.getString(R.string.XoSecretFingerprintHeader));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 6));

        // fingerprint
        fingerprintView = new TextView(context);
        fingerprintView.setTextSize(20);
        fingerprintView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fingerprintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            fingerprintView.setLetterSpacing(0.12f);
        }
        fingerprintView.setLineSpacing(AndroidUtilities.dp(6), 1f);
        fingerprintView.setGravity(Gravity.CENTER);
        fingerprintView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        fingerprintView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        layout.addView(fingerprintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        hintView = new TextView(context);
        hintView.setTextSize(13);
        hintView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        hintView.setText(LocaleController.getString(R.string.XoSecretFingerprintHint));
        layout.addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        String fingerprint = secret.safetyNumber(selfId, peerUserId);
        if (fingerprint != null && !fingerprint.isEmpty()) {
            // 15 groups of 4 hex chars -> 3 groups of 5 per line
            String[] parts = fingerprint.split(" ");
            StringBuilder pretty = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    pretty.append(i % 5 == 0 ? "\n" : " ");
                }
                pretty.append(parts[i]);
            }
            fingerprintView.setText(pretty.toString());
        } else {
            fingerprintView.setText(LocaleController.getString(R.string.XoE2eeNotReady));
        }

        if (secret.isKeyChanged(peerUserId)) {
            warningView.setVisibility(View.VISIBLE);
            warningView.setText(LocaleController.getString(R.string.XoSecretKeyChanged));
            warningView.setTextColor(0xFFFFFFFF);
            warningView.setBackgroundColor(0xFFE53935);
        }

        org.telegram.tgnet.TLRPC.User peer = getMessagesController().getUser(peerUserId);
        if (peer != null) {
            actionBar.setTitle(peer.first_name + " — " + LocaleController.getString(R.string.XoSecretTitle));
        }
        fragmentView = scrollView;
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }
}
