package org.telegram.ui;

import android.content.Context;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ImageUpdater;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;

/**
 * T35 — the basic-group info editor, built FOR this fork's backend (root
 * rewrite of the group-edit half of ChatEditActivity, which is a channel
 * editor: its basic-group path either hid the description save behind an
 * unrouted request or tried to convert the group).
 *
 * Scope, Telegram-aligned and backend-exact:
 *  - avatar: ImageUploader → crop → changeChatAvatar (TL_messages_editChatPhoto,
 *    routed); menu offers delete → TL_inputChatPhotoEmpty → backend remove;
 *  - name: TL_messages_editChatTitle (routed, T32);
 *  - description: TL_messages_editChatAbout (routed in T35, backend chats.about);
 *  - members/administrators rows: ChatUsersActivity reads the cached chatFull
 *    (served by the T35 getFullChat route).
 *
 * Unlike MessagesController.changeChatTitle/updateChatAbout (whose callbacks
 * swallow errors), this fragment OWNS its requests: failures surface as an
 * error bulletin and the screen stays editable — no silent spinner.
 */
public class XoGroupEditActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ImageUpdater.ImageUpdaterDelegate {

    private final static int done_button = 1;

    private long chatId;
    private TLRPC.Chat currentChat;
    private TLRPC.ChatFull info;

    private EditTextBoldCursor nameTextView;
    private EditTextBoldCursor descriptionTextView;
    private BackupImageView avatarImage;
    private View avatarOverlay;
    private RadialProgressView avatarProgressView;
    private AvatarDrawable avatarDrawable;
    private TLRPC.FileLocation avatar;

    private ImageUpdater imageUpdater;
    private boolean donePressed;
    private AlertDialog progressDialog;

    private TextCell membersCell;
    private TextCell administratorsCell;

    public XoGroupEditActivity(Bundle args) {
        super();
        chatId = args.getLong("chat_id", 0);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        currentChat = getMessagesController().getChat(chatId);
        if (currentChat == null) {
            return false;
        }
        info = getMessagesController().getChatFull(chatId);
        if (info == null) {
            getMessagesController().loadFullChat(chatId, getClassGuid(), true);
        }
        avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(5, currentChat.title, null);
        imageUpdater = new ImageUpdater(false, ImageUpdater.FOR_TYPE_GROUP, true);
        imageUpdater.parentFragment = this;
        imageUpdater.setDelegate(this);
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        if (imageUpdater != null) {
            imageUpdater.clear();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("ManageGroup", R.string.ManageGroup));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    processDone();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem item = menu.addItem(done_button, R.drawable.ic_ab_done);
        item.setContentDescription(LocaleController.getString("Save", R.string.Save));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = scrollView;

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        boolean canEdit = ChatObject.canChangeChatInfo(currentChat);

        // ---- avatar -------------------------------------------------------
        FrameLayout avatarContainer = new FrameLayout(context);
        avatarContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        linearLayout.addView(avatarContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 8));

        avatarImage = new BackupImageView(context) {
            @Override
            public void invalidate() {
                if (avatarOverlay != null) {
                    avatarOverlay.invalidate();
                }
                super.invalidate();
            }

            @Override
            public void invalidate(int l, int t, int r, int b) {
                if (avatarOverlay != null) {
                    avatarOverlay.invalidate();
                }
                super.invalidate(l, t, r, b);
            }
        };
        avatarImage.setRoundRadius(AndroidUtilities.dp(32));
        avatarContainer.addView(avatarImage, LayoutHelper.createFrame(64, 64, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 0, 0, 0));

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x55000000);
        avatarOverlay = new View(context) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                if (avatarImage != null && avatarImage.getImageReceiver().hasNotThumb()) {
                    paint.setAlpha((int) (0x55 * avatarImage.getImageReceiver().getCurrentAlpha()));
                    canvas.drawCircle(getMeasuredWidth() / 2.0f, getMeasuredHeight() / 2.0f, getMeasuredWidth() / 2.0f, paint);
                }
            }
        };
        avatarContainer.addView(avatarOverlay, LayoutHelper.createFrame(64, 64, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 0, 0, 0));

        avatarProgressView = new RadialProgressView(context);
        avatarProgressView.setSize(AndroidUtilities.dp(30));
        avatarProgressView.setProgressColor(0xffffffff);
        avatarProgressView.setNoProgress(false);
        avatarContainer.addView(avatarProgressView, LayoutHelper.createFrame(64, 64, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 0, 0, 0));
        showAvatarProgress(false, false);

        avatarContainer.setOnClickListener(v -> {
            if (imageUpdater.isUploadingImage()) {
                return;
            }
            imageUpdater.openMenu(avatar != null, () -> {
                performAvatarRemove();
            }, null, 0);
        });

        // ---- name ----------------------------------------------------------
        nameTextView = new EditTextBoldCursor(context);
        nameTextView.setTextSize(16);
        nameTextView.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameTextView.setBackgroundDrawable(null);
        nameTextView.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        nameTextView.setMaxLines(1);
        nameTextView.setLines(1);
        nameTextView.setSingleLine(true);
        nameTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        nameTextView.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameTextView.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameTextView.setHint(LocaleController.getString("GroupName", R.string.GroupName));
        nameTextView.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameTextView.setCursorSize(AndroidUtilities.dp(20));
        nameTextView.setCursorWidth(1.5f);
        nameTextView.setText(currentChat.title);
        nameTextView.setEnabled(canEdit);
        nameTextView.setFocusable(canEdit);
        nameTextView.requestFocus();
        linearLayout.addView(nameTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 24, 12, 24, 0));

        // ---- description -----------------------------------------------------
        descriptionTextView = new EditTextBoldCursor(context);
        descriptionTextView.setTextSize(16);
        descriptionTextView.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        descriptionTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        descriptionTextView.setBackgroundDrawable(null);
        descriptionTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        descriptionTextView.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        descriptionTextView.setImeOptions(EditorInfo.IME_ACTION_DONE);
        descriptionTextView.setHint(LocaleController.getString("DescriptionOptionalPlaceholder", R.string.DescriptionOptionalPlaceholder));
        descriptionTextView.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        descriptionTextView.setCursorSize(AndroidUtilities.dp(20));
        descriptionTextView.setCursorWidth(1.5f);
        descriptionTextView.setEnabled(canEdit);
        descriptionTextView.setFocusable(canEdit);
        android.text.InputFilter[] filters = new android.text.InputFilter[1];
        filters[0] = new android.text.InputFilter.LengthFilter(255);
        descriptionTextView.setFilters(filters);
        if (info != null && info.about != null) {
            descriptionTextView.setText(info.about);
        }
        linearLayout.addView(descriptionTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 14, 24, 12));

        // ---- members / administrators ---------------------------------------
        HeaderCell headerCell = new HeaderCell(context);
        headerCell.setHeight(46);
        headerCell.setText(LocaleController.getString("ChannelMembers", R.string.ChannelMembers));
        linearLayout.addView(headerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        membersCell = new TextCell(context);
        membersCell.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putLong("chat_id", chatId);
            args.putInt("type", ChatUsersActivity.TYPE_USERS);
            ChatUsersActivity fragment = new ChatUsersActivity(args);
            fragment.setInfo(info);
            presentFragment(fragment);
        });
        linearLayout.addView(membersCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        administratorsCell = new TextCell(context);
        administratorsCell.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putLong("chat_id", chatId);
            args.putInt("type", ChatUsersActivity.TYPE_ADMIN);
            ChatUsersActivity fragment = new ChatUsersActivity(args);
            fragment.setInfo(info);
            presentFragment(fragment);
        });
        linearLayout.addView(administratorsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateMembersCells();
        setAvatar();

        return fragmentView;
    }

    private void updateMembersCells() {
        if (membersCell == null || administratorsCell == null) {
            return;
        }
        int count = info != null && info.participants != null && info.participants.participants != null
                ? info.participants.participants.size()
                : currentChat.participants_count;
        membersCell.setTextAndValue(LocaleController.formatPluralString("Members", Math.max(count, 0)),
                info != null && info.participants != null && info.participants.participants != null
                        ? Integer.toString(info.participants.participants.size()) : "", true);
        int admins = 0;
        if (info != null && info.participants != null && info.participants.participants != null) {
            for (int a = 0, N = info.participants.participants.size(); a < N; a++) {
                TLRPC.ChatParticipant p = info.participants.participants.get(a);
                if (p instanceof TLRPC.TL_chatParticipantAdmin || p instanceof TLRPC.TL_chatParticipantCreator) {
                    admins++;
                }
            }
        }
        administratorsCell.setTextAndValue(LocaleController.getString("ChannelAdministrators", R.string.ChannelAdministrators),
                Integer.toString(admins), false);
    }

    private void setAvatar() {
        if (avatarImage == null) {
            return;
        }
        TLRPC.Chat chat = getMessagesController().getChat(chatId);
        if (chat == null) {
            return;
        }
        currentChat = chat;
        if (chat.photo != null) {
            avatar = chat.photo.photo_small;
            avatarImage.setForUserOrChat(chat, avatarDrawable);
        } else {
            avatarImage.setImageDrawable(avatarDrawable);
            avatar = null;
        }
    }

    private void showAvatarProgress(boolean show, boolean animated) {
        if (avatarProgressView == null) {
            return;
        }
        avatarProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
        avatarOverlay.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    /** Delete-photo path: all-null changeChatAvatar produces TL_inputChatPhotoEmpty → backend remove. */
    private void performAvatarRemove() {
        showAvatarProgress(true, true);
        getMessagesController().changeChatAvatar(chatId, null, null, null, null, 0, null, null, null, () -> {
            AndroidUtilities.runOnUIThread(() -> showAvatarProgress(false, true));
        });
    }

    private void processDone() {
        if (donePressed || nameTextView == null) {
            return;
        }
        if (nameTextView.length() == 0) {
            android.os.Vibrator v = (android.os.Vibrator) getParentActivity().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(200);
            }
            AndroidUtilities.shakeView(nameTextView);
            return;
        }
        donePressed = true;

        boolean titleChanged = currentChat != null && !currentChat.title.equals(nameTextView.getText().toString());
        String newAbout = descriptionTextView.getText().toString();
        String oldAbout = info != null && info.about != null ? info.about : "";
        boolean aboutChanged = !oldAbout.equals(newAbout);

        int[] remaining = {(titleChanged ? 1 : 0) + (aboutChanged ? 1 : 0)};
        if (remaining[0] == 0) {
            finishFragment();
            return;
        }

        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setOnCancelListener(dialog -> {
            donePressed = false;
            progressDialog = null;
        });
        progressDialog.show();

        Runnable onOneDone = () -> AndroidUtilities.runOnUIThread(() -> {
            remaining[0]--;
            if (remaining[0] <= 0) {
                finishSave();
            }
        });

        if (titleChanged) {
            TLRPC.TL_messages_editChatTitle req = new TLRPC.TL_messages_editChatTitle();
            req.chat_id = chatId;
            req.title = nameTextView.getText().toString();
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    finishSaveWithError(error.text);
                    return;
                }
                if (response instanceof TLRPC.TL_updates) {
                    getMessagesController().processUpdates((TLRPC.TL_updates) response, false);
                }
                onOneDone.run();
            }), ConnectionsManager.RequestFlagInvokeAfter);
        }

        if (aboutChanged) {
            TLRPC.TL_messages_editChatAbout req = new TLRPC.TL_messages_editChatAbout();
            req.peer = getMessagesController().getInputPeer(-chatId);
            req.about = newAbout;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    finishSaveWithError(error.text);
                    return;
                }
                // same local patch the shared updateChatAbout does on success
                TLRPC.ChatFull full = getMessagesController().getChatFull(chatId);
                if (full != null) {
                    full.about = newAbout;
                    getMessagesStorage().updateChatInfo(full, false);
                    getNotificationCenter().postNotificationName(NotificationCenter.chatInfoDidLoad, full, 0, false, false);
                }
                onOneDone.run();
            }), ConnectionsManager.RequestFlagInvokeAfter);
        }
    }

    private void finishSave() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignore) {
            }
            progressDialog = null;
        }
        donePressed = false;
        finishFragment();
    }

    private void finishSaveWithError(String message) {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignore) {
            }
            progressDialog = null;
        }
        donePressed = false;
        BulletinFactory.of(XoGroupEditActivity.this).createErrorBulletin(message != null ? message : "SERVER_ERROR").show();
    }

    @Override
    public void didUploadPhoto(TLRPC.InputFile photo, TLRPC.InputFile video, double videoStartTimestamp, String videoPath, TLRPC.PhotoSize bigSize, TLRPC.PhotoSize smallSize, boolean isVideo, TLRPC.VideoSize emojiMarkup) {
        AndroidUtilities.runOnUIThread(() -> {
            if (photo != null || video != null || emojiMarkup != null) {
                avatar = smallSize.location;
                getMessagesController().changeChatAvatar(chatId, null, photo, video, emojiMarkup, videoStartTimestamp, videoPath, smallSize.location, bigSize.location, null);
                showAvatarProgress(false, true);
            } else {
                showAvatarProgress(false, true);
            }
        });
    }

    @Override
    public String getInitialSearchString() {
        return nameTextView != null ? nameTextView.getText().toString() : "";
    }

    @Override
    public void onUploadProgressChanged(float progress) {
        if (avatarProgressView != null) {
            avatarProgressView.setProgress(progress);
        }
    }

    @Override
    public void didStartUpload(boolean isVideo) {
        AndroidUtilities.runOnUIThread(() -> showAvatarProgress(true, false));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.chatInfoDidLoad) {
            TLRPC.ChatFull chatFull = (TLRPC.ChatFull) args[0];
            if (chatFull != null && chatFull.id == chatId) {
                boolean wasEmpty = info == null;
                info = chatFull;
                if (descriptionTextView != null && !descriptionTextView.hasFocus() && (wasEmpty || !descriptionTextView.isFocused())) {
                    descriptionTextView.setText(chatFull.about != null ? chatFull.about : "");
                }
                updateMembersCells();
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            int mask = (Integer) args[0];
            if ((mask & MessagesController.UPDATE_MASK_AVATAR) != 0) {
                setAvatar();
            }
        }
    }
}
