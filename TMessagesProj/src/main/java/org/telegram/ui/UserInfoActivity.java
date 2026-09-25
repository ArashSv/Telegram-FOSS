/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

/*
 * T34 root rewrite (v1.7 profile contract). The upstream screen assumed a
 * full MTProto userFull before painting anything and drove several
 * server-side features this backend does not model (birthday, personal
 * channel, business rows) — in the field it painted empty fields and froze
 * its spinner on a null guard placed after the spinner was started.
 *
 * This rewrite matches OUR data model one-to-one:
 *   Name  — a single field; the backend stores exactly one display_name, so
 *           the upstream First/Last split was a lossy lie (joins on save,
 *           degrades on every round-trip).
 *   Bio   — 70 chars, "" clears, rides the same request (one round-trip).
 *   ID    — the account's numeric identity (5-digit, Telegram-style),
 *           copy-on-tap.
 *
 * Robustness rules the old file violated:
 *   - prefill synchronously from UserConfig.getCurrentUser() (always
 *     available once XoSelf owns the self slot); userFull about merges in
 *     later via userInfoDidLoad without clobbering user input;
 *   - the done spinner is reset BEFORE any early return — it can never spin
 *     forever;
 *   - exactly one TL_account_updateProfile per save; the response is applied
 *     through the XoSelf funnel (dispatcher-side), so the self user stays
 *     whole (self flag + phone).
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CircularProgressDrawable;
import org.telegram.ui.Components.CrossfadeDrawable;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Calendar;

public class UserInfoActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int BUTTON_COPY_ID = 1;

    private EditTextCell nameEdit;
    private EditTextCell bioEdit;

    private String currentName;
    private String currentBio;

    private static final int done_button = 1;
    private CrossfadeDrawable doneButtonDrawable;
    private ActionBarMenuItem doneButton;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.EditProfileInfo);
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.userInfoDidLoad);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.userInfoDidLoad);
        super.onFragmentDestroy();
        if (!wasSaved) {
            processDone(false);
        }
    }

    @Override
    public View createView(Context context) {
        Theme.ResourcesProvider resourceProvider = getResourceProvider();

        nameEdit = new EditTextCell(context, getString(R.string.EditProfileName), false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                checkDone(true);
            }
        };
        nameEdit.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        nameEdit.setDivider(true);
        nameEdit.hideKeyboardOnEnter();

        bioEdit = new EditTextCell(context, getString(R.string.EditProfileBioHint), true, getMessagesController().getAboutLimit(), resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                checkDone(true);
            }
        };
        bioEdit.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        bioEdit.setShowLimitWhenEmpty(true);

        super.createView(context);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed()) {
                        finishFragment();
                    }
                } else if (id == done_button) {
                    processDone(true);
                }
            }
        });

        Drawable checkmark = context.getResources().getDrawable(R.drawable.ic_ab_done).mutate();
        checkmark.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon, resourceProvider), PorterDuff.Mode.MULTIPLY));
        doneButtonDrawable = new CrossfadeDrawable(checkmark, new CircularProgressDrawable(Theme.getColor(Theme.key_actionBarDefaultIcon, resourceProvider)));
        doneButton = actionBar.createMenu().addItemWithWidth(done_button, doneButtonDrawable, dp(56), getString(R.string.Done));
        checkDone(false);

        setValue();

        return fragmentView;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.EditProfileName)));
        items.add(UItem.asCustom(nameEdit));
        items.add(UItem.asHeader(getString(R.string.EditProfileBio)));
        items.add(UItem.asCustom(bioEdit));
        items.add(UItem.asShadow(-1, null));
        items.add(UItem.asButton(BUTTON_COPY_ID, getString(R.string.ProfileUserId), String.valueOf(getUserConfig().getClientUserId())));
        items.add(UItem.asShadow(-2, null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_COPY_ID) {
            long id = getUserConfig().getClientUserId();
            AndroidUtilities.addToClipboard(String.valueOf(id));
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.ProfileUserIdCopied)).show();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /**
     * Upstream static utility still consumed by ProfileActivity (birthday
     * display rows for users whose birthday the full-user payload carries).
     * The edit screen itself no longer edits birthdays — no backend contract.
     */
    public static String birthdayString(TLRPC.TL_birthday birthday) {
        if (birthday == null) {
            return "—";
        }
        if ((birthday.flags & 1) != 0) {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.YEAR, birthday.year);
            calendar.set(Calendar.MONTH, birthday.month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, birthday.day);
            return LocaleController.getInstance().getFormatterBoostExpired().format(calendar.getTimeInMillis());
        } else {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.MONTH, birthday.month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, birthday.day);
            return LocaleController.getInstance().getFormatterDayMonth().format(calendar.getTimeInMillis());
        }
    }

    /**
     * Synchronous prefill. The name ALWAYS paints from the current self user
     * (v1.6 froze on a null userFull and painted nothing). Bio paints from
     * the full-user payload when it is already cached; the userInfoDidLoad
     * observer back-fills it later without clobbering user input.
     */
    private void setValue() {
        TLRPC.User user = getUserConfig().getCurrentUser();
        if (user != null && !userIsForeign(user)) {
            String name = UserObject.getUserName(user);
            currentName = name == null ? "" : name;
            if (nameEdit != null) {
                nameEdit.setText(currentName);
            }
        }

        TLRPC.UserFull userFull = getMessagesController().getUserFull(getUserConfig().getClientUserId());
        String about = userFull != null ? userFull.about : null;
        currentBio = about == null ? "" : about;
        if (bioEdit != null && TextUtils.isEmpty(bioEdit.getText())) {
            bioEdit.setText(currentBio);
        }

        checkDone(true);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    /** The self user is the only thing this screen edits. */
    private boolean userIsForeign(TLRPC.User user) {
        return user == null || user.id != getUserConfig().getClientUserId();
    }

    public boolean hasChanges() {
        return
            !TextUtils.equals(currentName == null ? "" : currentName, nameText()) ||
            !TextUtils.equals(currentBio == null ? "" : currentBio, bioText());
    }

    private String nameText() {
        return nameEdit != null && nameEdit.getText() != null ? nameEdit.getText().toString() : "";
    }

    private String bioText() {
        return bioEdit != null && bioEdit.getText() != null ? bioEdit.getText().toString() : "";
    }

    private void checkDone(boolean animated) {
        if (doneButton == null) {
            return;
        }
        final boolean hasChanges = hasChanges();
        doneButton.setEnabled(hasChanges);
        if (animated) {
            doneButton.animate().alpha(hasChanges ? 1.0f : 0.0f).scaleX(hasChanges ? 1.0f : 0.0f).scaleY(hasChanges ? 1.0f : 0.0f).setDuration(180).start();
        } else {
            doneButton.setAlpha(hasChanges ? 1.0f : 0.0f);
            doneButton.setScaleX(hasChanges ? 1.0f : 0.0f);
            doneButton.setScaleY(hasChanges ? 1.0f : 0.0f);
        }
    }

    private boolean wasSaved = false;
    private int shiftDp = -4;

    private void processDone(boolean error) {
        if (doneButtonDrawable == null || doneButtonDrawable.getProgress() > 0f) {
            return;
        }

        final String newName = nameText();
        final String newBio = bioText();
        final boolean nameChanged = !TextUtils.equals(currentName == null ? "" : currentName, newName);
        final boolean bioChanged = !TextUtils.equals(currentBio == null ? "" : currentBio, newBio);
        final boolean nothingToDo = !nameChanged && !bioChanged;

        if (error && TextUtils.isEmpty(newName.trim())) {
            BotWebViewVibrationEffect.APP_ERROR.vibrate();
            AndroidUtilities.shakeViewSpring(nameEdit, shiftDp = -shiftDp);
            return;
        }

        // T34 fix: the spinner starts only when we are certain a request (or
        // a plain finish) follows; every early return below resets it first.
        doneButtonDrawable.animateToProgress(1f);

        if (nothingToDo) {
            doneButtonDrawable.animateToProgress(0f);
            finishFragment();
            return;
        }

        TLRPC.User user = getUserConfig().getCurrentUser();
        if (user == null || user.id != getUserConfig().getClientUserId()) {
            doneButtonDrawable.animateToProgress(0f);
            return;
        }

        if (TextUtils.isEmpty(newName.trim())) {
            // auto-save path with an emptied name: keep the stored name, still
            // allow a bio-only change
            if (!bioChanged) {
                doneButtonDrawable.animateToProgress(0f);
                finishFragment();
                return;
            }
        }

        TLRPC.TL_account_updateProfile req = new TLRPC.TL_account_updateProfile();
        if (nameChanged && !TextUtils.isEmpty(newName.trim())) {
            // v1.7 identity model: one display_name — no lossy First/Last join.
            req.flags |= 1;
            req.first_name = newName.trim();
            // optimistic local apply (flags kept coherent for serialization)
            user.first_name = req.first_name;
            user.last_name = null;
            user.flags |= 2;
            user.flags &= ~4;
        }
        if (bioChanged) {
            req.flags |= 4;
            req.about = newBio;
            TLRPC.UserFull userFull = getMessagesController().getUserFull(getUserConfig().getClientUserId());
            if (userFull != null) {
                userFull.about = newBio;
                userFull.flags = TextUtils.isEmpty(newBio) ? (userFull.flags & ~2) : (userFull.flags | 2);
                getMessagesStorage().updateUserInfo(userFull, false);
            }
        }

        if ((req.flags & 3) == 0 && (req.flags & 4) == 0) {
            doneButtonDrawable.animateToProgress(0f);
            finishFragment();
            return;
        }

        // name edits reflect immediately across the UI; the merged backend
        // response (via XoSelf) then settles the authoritative state
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);

        getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (doneButtonDrawable != null) {
                doneButtonDrawable.animateToProgress(0f);
            }
            if (err != null) {
                BulletinFactory.showError(err);
                wasSaved = false;
                return;
            }
            wasSaved = true;
            currentName = nameText();
            currentBio = bioText();
            checkDone(true);
            finishFragment();
        }), ConnectionsManager.RequestFlagDoNotWaitFloodWait);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.userInfoDidLoad) {
            setValue();
        }
    }

    // ------------------------------------------------------------------
    // dialog-lifecycle plumbing kept from upstream (resume/sleep pauses the
    // keyboard focus loop); rows for features without a backend contract
    // (birthday, personal channel, business) are gone — they could only ever
    // error against this backend.
    // ------------------------------------------------------------------

    @Override
    public void onPause() {
        super.onPause();
        if (nameEdit != null) {
            nameEdit.clearFocus();
        }
        if (bioEdit != null) {
            bioEdit.clearFocus();
        }
    }
}
