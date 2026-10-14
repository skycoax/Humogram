package uz.jac.secure.android;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.TwoStepVerificationSetupActivity;

/**
 * The short film behind "Learn more" on the cloud-password reminder: why a
 * login code alone is not enough, and the three steps of Telegram's own setup,
 * in the language the app is set to. At the end, one button opens that setup.
 *
 * <h3>Streamed, not shipped</h3>
 *
 * The film lives on the project site ({@link #FILM_BASE}), one file per
 * language, and is streamed when this page opens: three films in the app would
 * have made every install about 9 MB bigger for a page most people open once.
 * The request carries nothing but the file name — the site sees an IP address
 * the way any web page does — and goes over https with system CAs only
 * ({@code jac_network_security_config}). With no connection the page says so,
 * a tap tries again, and "Set up" is offered anyway: the film is a help, never
 * a gate in front of the password.
 *
 * <h3>Built from Telegram's parts</h3>
 *
 * A {@link BaseFragment}, so it slides in and swipes back like every other
 * page; the fragment's own {@link ActionBar} with {@link BackDrawable}, laid
 * transparently over the film; Telegram's {@link VideoPlayer} (ExoPlayer) on a
 * {@link TextureView}; {@link RadialProgressView} while it loads; and
 * {@link ButtonWithCounterView}, the button upstream's sheets use.
 *
 * <p>The page colour is the film's own paper white, not a theme colour: the
 * film is light by design, and its edges must melt into the letterbox in
 * either theme, the way a photo viewer's black does.
 */
public class TwoStepVideoActivity extends BaseFragment {

    /** Where the films are: {@code 2fa-uz.mp4}, {@code 2fa-ru.mp4}, {@code 2fa-en.mp4}. */
    static final String FILM_BASE = "https://hg.skycoax.uz/media/";

    /** The film's background (#FCFAF4, the site's "paper"). */
    private static final int PAPER = 0xFFFCFAF4;
    /** The film's ink (#12275A): back arrow, spinner and messages. */
    private static final int INK = 0xFF12275A;

    private final TL_account.Password password;

    private VideoPlayer player;
    private TextureView textureView;
    private RadialProgressView progress;
    private TextView errorView;
    private ButtonWithCounterView setUpButton;
    private boolean ended;
    private boolean failed;

    /** @param password the account's password info, salted for the wizard (see SecurityCheckup) */
    public TwoStepVideoActivity(TL_account.Password password) {
        this.password = password;
    }

    /** The film in the language the app is set to: Uzbek, Russian, or English for everyone else. */
    static String filmUrl() {
        String lang = "en";
        try {
            lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
        } catch (Throwable ignored) {
        }
        final String file;
        if ("uz".equals(lang)) {
            file = "uz";
        } else if ("ru".equals(lang) || "uk".equals(lang) || "be".equals(lang) || "kk".equals(lang)) {
            file = "ru";
        } else {
            file = "en";
        }
        return FILM_BASE + "2fa-" + file + ".mp4";
    }

    @Override
    public ActionBar createActionBar(Context context) {
        final ActionBar actionBar = new ActionBar(context);
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setItemsColor(INK, false);
        actionBar.setItemsBackgroundColor(0x1F12275A, false);
        actionBar.setAddToContainer(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        return actionBar;
    }

    @Override
    public View createView(Context context) {
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(PAPER);

        final AspectRatioFrameLayout aspect = new AspectRatioFrameLayout(context);
        aspect.setAspectRatio(9f / 16f, 0);
        aspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(aspect, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        textureView = new TextureView(context);
        textureView.setKeepScreenOn(true);
        aspect.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        aspect.setOnClickListener(v -> togglePlay());

        progress = new RadialProgressView(context);
        progress.setProgressColor(INK);
        progress.setSize(dp(36));
        root.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        errorView.setTextColor(INK);
        errorView.setGravity(Gravity.CENTER);
        errorView.setText(JacStrings.get(context, R.string.jac_2fa_video_error));
        errorView.setVisibility(View.GONE);
        root.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 40, 0, 40, 0));

        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        setUpButton = new ButtonWithCounterView(context, getResourceProvider());
        setUpButton.setText(JacStrings.get(context, R.string.jac_checkup_set_up), false);
        setUpButton.setOnClickListener(v -> openSetup());
        setUpButton.setAlpha(0f);
        setUpButton.setVisibility(View.GONE);
        root.addView(setUpButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 0, 16, 24));

        fragmentView = root;
        startPlayer();
        return root;
    }

    private void startPlayer() {
        failed = false;
        ended = false;
        setLoading(true);
        errorView.setVisibility(View.GONE);
        try {
            if (player != null) {
                player.releasePlayer(true);
            }
            player = new VideoPlayer();
            player.setTextureView(textureView);
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    if (playbackState == ExoPlayer.STATE_READY) {
                        setLoading(false);
                    } else if (playbackState == ExoPlayer.STATE_BUFFERING) {
                        setLoading(true);
                    } else if (playbackState == ExoPlayer.STATE_ENDED) {
                        onEnded();
                    }
                }

                @Override
                public void onError(VideoPlayer p, Exception e) {
                    FileLog.e(e);
                    onFailed();
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                }

                @Override
                public void onRenderedFirstFrame() {
                    setLoading(false);
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                }

                @Override
                public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                    return false;
                }
            });
            player.preparePlayer(Uri.parse(filmUrl()), "other");
            player.setPlayWhenReady(true);
        } catch (Throwable t) {
            FileLog.e(t);
            onFailed();
        }
    }

    private void setLoading(boolean loading) {
        if (progress != null) {
            progress.setVisibility(loading && !failed ? View.VISIBLE : View.GONE);
        }
    }

    private void togglePlay() {
        if (failed) {
            startPlayer();
            return;
        }
        if (player == null) {
            return;
        }
        if (ended) {
            ended = false;
            showSetUp(false);
            player.seekTo(0);
            player.play();
        } else if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void onEnded() {
        ended = true;
        showSetUp(true);
    }

    private void onFailed() {
        failed = true;
        setLoading(false);
        if (errorView != null) {
            errorView.setVisibility(View.VISIBLE);
        }
        showSetUp(true);
    }

    private void showSetUp(boolean show) {
        if (setUpButton == null) {
            return;
        }
        setUpButton.animate().cancel();
        if (show) {
            setUpButton.setVisibility(View.VISIBLE);
            setUpButton.setTranslationY(dp(12));
            setUpButton.animate().alpha(1f).translationY(0).setDuration(320).start();
        } else {
            setUpButton.animate().alpha(0f).setDuration(180)
                    .withEndAction(() -> setUpButton.setVisibility(View.GONE)).start();
        }
    }

    private void openSetup() {
        if (password == null) {
            finishFragment();
            return;
        }
        final int type = android.text.TextUtils.isEmpty(password.email_unconfirmed_pattern)
                ? TwoStepVerificationSetupActivity.TYPE_INTRO
                : TwoStepVerificationSetupActivity.TYPE_EMAIL_CONFIRM;
        presentFragment(new TwoStepVerificationSetupActivity(type, password), true);
    }

    @Override
    public boolean isLightStatusBar() {
        return true;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player != null && !ended && !failed) {
            player.play();
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (player != null) {
            player.releasePlayer(true);
            player = null;
        }
        super.onFragmentDestroy();
    }
}
