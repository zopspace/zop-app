package fi.aalto.legroup.zop.playback;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.PointF;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewStub;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.nispok.snackbar.Snackbar;
import com.nispok.snackbar.SnackbarManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import fi.aalto.legroup.zop.R;
import fi.aalto.legroup.zop.app.App;
import fi.aalto.legroup.zop.authoring.QRHelper;
import fi.aalto.legroup.zop.authoring.VideoDeletionFragment;
import fi.aalto.legroup.zop.authoring.VideoHelper;
import fi.aalto.legroup.zop.browsing.DetailActivity;
import fi.aalto.legroup.zop.browsing.DiscussionView;
import fi.aalto.legroup.zop.entities.Annotation;
import fi.aalto.legroup.zop.entities.Video;
import fi.aalto.legroup.zop.storage.VideoRepository;
import fi.aalto.legroup.zop.utilities.RepeatingTask;
import fi.aalto.legroup.zop.utilities.TranslationHelper;
import fi.aalto.legroup.zop.views.MarkedSeekBar;

/**
 * Handles view logic for the video player controls. Actual playback is handled by
 * VideoPlayerFragment.
 *
 * TODO: Extract annotation editing into a separate fragment.
 */
public final class PlayerActivity extends ActionBarActivity implements AnnotationEditor,
        PlayerFragment.PlaybackStateListener, SeekBar.OnSeekBarChangeListener,
        View.OnClickListener {

    public static final String ARG_VIDEO_ID = "ARG_VIDEO_ID";

    // How long the user can be inactive before the controls get hidden (in milliseconds)
    private static final int CONTROLS_HIDE_DELAY = 5000;

    // Animation duration for hiding and showing controls (in milliseconds)
    private static final int CONTROLS_HIDE_DURATION = 300;

    private PlayerFragment playerFragment;

    private LinearLayout playbackControls;

    // Can be either a ViewStub or an inflated LinearLayout
    private View annotationControls;

    private long lastAnnotationCreateTime = -6666;
    private ImageButton playPauseButton;
    private TextView elapsedTimeText;
    private MarkedSeekBar seekBar;

    private Toolbar toolbar;
    private Button deleteButton;
    private Button saveButton;
    private EditText annotationText;

    private Video video;

    private Uri intentFile;
    private String intentType;

    private Handler controllerVisibilityHandler = new Handler();
    private SeekBarUpdater seekBarUpdater = new SeekBarUpdater();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        App.bus.register(this);
        setContentView(R.layout.activity_player);

        Intent intent = this.getIntent();
        this.intentFile = intent.getData();
        this.intentType = intent.getType();

        this.toolbar = (Toolbar) this.findViewById(R.id.toolbar);

        setSupportActionBar(this.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        playbackControls = (LinearLayout) findViewById(R.id.playbackControls);
        annotationControls = findViewById(R.id.annotationControls);

        playPauseButton = (ImageButton) findViewById(R.id.playPauseButton);
        elapsedTimeText = (TextView) findViewById(R.id.elapsedTimeText);
        seekBar = (MarkedSeekBar) findViewById(R.id.seekBar);

        playPauseButton.setOnClickListener(this);

        seekBar.setOnSeekBarChangeListener(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_player, menu);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.bus.register(this);
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();

        if (this.intentFile != null) {
            if (this.intentFile.getScheme().equals("file")) {
                String filename = new File(this.intentFile.getPath()).getName();
                VideoHelper.moveFile(this.intentFile, App.localStorageDirectory.getPath() + "/");
                UUID videoId = VideoHelper.unpackAchsoFile(filename);
                loadVideo(videoId);
                // We have created a new file in the local storage directory, so try to reload.
                App.videoRepository.refreshOffline();
            } else {
                App.videoRepository.findVideoByVideoUri(this.intentFile, this.intentType, new FindVideoCallback());
            }
        } else {
            loadVideo((UUID) getIntent().getSerializableExtra(ARG_VIDEO_ID));
        }
    }

    protected class FindVideoCallback implements VideoRepository.VideoCallback {

        @Override
        public void found(Video video) {
            loadVideo(video);
        }

        @Override
        public void notFound() {
            SnackbarManager.show(Snackbar.with(PlayerActivity.this).text(R.string.video_find_error));
            finish();
        }
    }

    protected void loadVideo(UUID videoId) {
        Video video;
        try {
            video = App.videoRepository.getVideo(videoId).inflate();

        } catch (IOException e) {
            e.printStackTrace();
            SnackbarManager.show(Snackbar.with(this).text(R.string.storage_error));
            finish();
            return;
        }

        loadVideo(video);
    }

    private static class DownloadUpdatedVideoAsync extends AsyncTask<Video, Void, Video> {
        @Override
        protected Video doInBackground(Video... videos) {
            // Expect only one argument
            Video video = videos[0];

            // HACK: Just use Achrails directly, this does not use the result at the moment, but is
            // used only for posting the view statistics.
            try {
                return App.achRails.downloadVideoManifestIfNewerThan(video.getId(), video.getRevision(), true);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    protected void loadVideo(Video video) {

        this.video = video;

        new DownloadUpdatedVideoAsync().execute(video);

        populateVideoInformation();
        playerFragment = (PlayerFragment)
                getFragmentManager().findFragmentById(R.id.videoPlayerFragment);

        playerFragment.setListener(this);
        playerFragment.prepare(video, this);
    }

    @Override
    protected void onPause() {
        App.bus.unregister(this);
        seekBarUpdater.stop();
        controllerVisibilityHandler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        List<UUID> videos = Collections.singletonList(video.getId());

        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            /*
            case R.id.action_share:
                ExportService.export(this, video.getId());
                return true;

            case R.id.action_upload:
                return true;

            */

            case R.id.action_view_video_info:
                Intent informationIntent = new Intent(this, DetailActivity.class);
                informationIntent.putExtra(DetailActivity.ARG_VIDEO_ID, video.getId());
                startActivity(informationIntent);
                return true;

            case R.id.action_delete:
                VideoDeletionFragment.newInstance(videos)
                        .show(getSupportFragmentManager(), "DeletionFragment");
                return true;

            case R.id.action_discussion:
                //pause the video if it was playing
                if ( playerFragment.getState() == PlayerFragment.State.PLAYING) {
                    this.togglePlayback();
                }else{
                    //forcefully pause the video. it is a repetition. can be fixed in next version.
                    System.out.println("Video Paused!");
                    playerFragment.pause();
                }
                //KH: launch discussion fragment here

                DiscussionView discussionView = new DiscussionView();
                discussionView.createDiscussionView(playerFragment, video);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Called when a view with this listener is clicked.
     */
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.playPauseButton:
                togglePlayback();
                break;
        }
    }

    private void populateVideoInformation() {
        TranslationHelper translationHelper = TranslationHelper.get(this);

        toolbar.setTitle(video.getTitle());
    }

    private void refreshAnnotations() {
        List<Annotation> annotations = video.getAnnotations();
        List<Integer> markers = new ArrayList<>();

        playerFragment.setAnnotations(annotations);

        for (Annotation annotation : annotations) {
            markers.add((int) annotation.getTime());
        }

        seekBar.setMarkers(markers);
    }

    public void togglePlayback() {
        //KH:
        System.out.println("Khawar ZoP: toggling video playback");
        if (playerFragment.getState() == PlayerFragment.State.PLAYING) {
            playerFragment.pause();
        } else {
            playerFragment.play();
        }
    }

    private void anchorSubtitleContainerTo(View view) {
        int height = 0;

        if (view != null) {
            height = view.getHeight();
        }

        playerFragment.getSubtitleContainer().setPadding(0, 0, 0, height);
    }

    /**
     * Show the controls overlay.
     */
    private void showControlsOverlay() {
        cancelControlsOverlayHide();

        playbackControls.animate().alpha(1).setDuration(CONTROLS_HIDE_DURATION).start();
        annotationControls.animate().alpha(1).setDuration(CONTROLS_HIDE_DURATION).start();
        toolbar.animate().alpha(1).setDuration(CONTROLS_HIDE_DURATION).start();

        anchorSubtitleContainerTo(playbackControls);
    }

    /**
     * Hide controls using a delay.
     */
    private void hideControlsOverlay() {
        controllerVisibilityHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Don't hide if we're paused
                if (playerFragment.getState() == PlayerFragment.State.PAUSED) {
                    return;
                }

                playbackControls.animate().alpha(0).setDuration(CONTROLS_HIDE_DURATION).start();
                annotationControls.animate().alpha(0).setDuration(CONTROLS_HIDE_DURATION).start();
                toolbar.animate().alpha(0).setDuration(CONTROLS_HIDE_DURATION).start();

                anchorSubtitleContainerTo(null);
            }
        }, CONTROLS_HIDE_DELAY);
    }

    /**
     * Cancel pending hiding operations.
     */
    private void cancelControlsOverlayHide() {
        controllerVisibilityHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        QRHelper.readQRCodeResult(this, requestCode, resultCode, data);
    }

    @Override
    public void createAnnotation(PointF position) {

        // No annotating while pause is already going on
        if (playerFragment.getState() == PlayerFragment.State.ANNOTATION_PAUSED) {
            return;

        } if (playerFragment.getState() != PlayerFragment.State.PAUSED) {
            playerFragment.pause();
            playerFragment.setState(PlayerFragment.State.PAUSED);
        }
        // Disallow creating annotations when an annotation is being edited
        if (areAnnotationControlsVisible()) {
            return;
        }

        long time = playerFragment.getPlaybackPosition();
        lastAnnotationCreateTime = time;

        Date now = new Date();
        Annotation annotation = new Annotation(time, position, "", App.loginManager.getUser(), now);

        video.getAnnotations().add(annotation);

        if (!video.save()) {
            SnackbarManager.show(Snackbar.with(this).text(R.string.storage_error));
        }

        editAnnotation(annotation);

        refreshAnnotations();
    }

    @Override
    public void moveAnnotation(Annotation annotation, PointF position) {
        annotation.setPosition(position);

        if (!video.save()) {
            SnackbarManager.show(Snackbar.with(this).text(R.string.storage_error));
        }

        refreshAnnotations();
    }

    @Override
    public void editAnnotation(final Annotation annotation) {
        // Allow editing annotations only when paused
        if (playerFragment.getState() != PlayerFragment.State.PAUSED) {
            return;
        }

        showAnnotationControls();

        annotationText.setText(annotation.getText());

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String text = annotationText.getText().toString();

                annotation.setText(text);

                if (!video.save()) {
                    SnackbarManager.show(Snackbar.with(PlayerActivity.this)
                            .text(R.string.storage_error));
                }

                refreshAnnotations();

                hideAnnotationControls();
            }
        });

        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                video.getAnnotations().remove(annotation);

                if (!video.save()) {
                    SnackbarManager.show(Snackbar.with(PlayerActivity.this)
                            .text(R.string.storage_error));
                }

                refreshAnnotations();

                hideAnnotationControls();
            }
        });
    }

    private void showAnnotationControls() {
        // Inflate the view stub if necessary
        if (annotationControls instanceof ViewStub) {
            ViewStub stub = (ViewStub) annotationControls;
            annotationControls = stub.inflate();

            annotationText = (EditText) findViewById(R.id.annotationText);
            saveButton = (Button) findViewById(R.id.saveButton);
            deleteButton = (Button) findViewById(R.id.deleteButton);
        }

        playbackControls.setVisibility(View.GONE);
        annotationControls.setVisibility(View.VISIBLE);

        anchorSubtitleContainerTo(annotationControls);
    }

    private void hideAnnotationControls() {
        playbackControls.setVisibility(View.VISIBLE);
        annotationControls.setVisibility(View.GONE);

        anchorSubtitleContainerTo(playbackControls);
    }

    private boolean areAnnotationControlsVisible() {
        if (annotationControls == null) {
            return false;
        }
        if (annotationControls.getVisibility() == View.VISIBLE) {
            return true;
        }
        return false;
    }

    private void enableControls() {
        seekBar.setEnabled(true);
        playPauseButton.setEnabled(true);
        playPauseButton.setImageAlpha(0xFF);
    }

    private void disableControls() {
        seekBar.setEnabled(false);
        playPauseButton.setEnabled(false);

        // Material design spec specifies 30% alpha for disabled icons
        playPauseButton.setImageAlpha(0x4D);
    }

    /**
     * Fired when the player fragment changes state.
     */
    @Override
    public void onPlaybackStateChanged(PlayerFragment.State state) {
        switch (state) {
            case PREPARED:
                // Initialise the seek bar now that we have a duration and a position
                seekBar.setMax((int) playerFragment.getDuration());
                seekBar.setProgress((int) playerFragment.getPlaybackPosition());
                seekBarUpdater.run();

                refreshAnnotations();
                anchorSubtitleContainerTo(playbackControls);
                break;

            case PLAYING:
                enableControls();
                playPauseButton.setImageResource(R.drawable.ic_action_pause);
                break;

            case PAUSED:
                if (lastAnnotationCreateTime != -6666) {
                    playerFragment.seekTo(lastAnnotationCreateTime);
                    lastAnnotationCreateTime = -6666;
                }
                showControlsOverlay();
                playPauseButton.setImageResource(R.drawable.ic_action_play);
                break;

            case ANNOTATION_PAUSED:
                disableControls();
                playPauseButton.setImageResource(R.drawable.ic_action_play);
                break;
        }
    }

    /**
     * Fired when the seek bar value changes. Updates the elapsed time to reflect the state of the
     * seek bar and tells the player fragment to seek if the change was user-initiated.
     */
    @Override
    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        long elapsedTime = (long) (progress / 1000f);
        String elapsedTimeString = DateUtils.formatElapsedTime(elapsedTime);

        elapsedTimeText.setText(elapsedTimeString);

        if (fromUser) {
            playerFragment.seekTo(progress);
        }
    }

    /**
     * Fired when the user starts seeking manually. Stops the seek bar updates so that the thumb
     * will stay in place.
     */
    @Override
    public void onStartTrackingTouch(SeekBar bar) {
        seekBarUpdater.stop();
    }

    /**
     * Fired when the user stops seeking manually. Starts updating the seek bar again.
     */
    @Override
    public void onStopTrackingTouch(SeekBar bar) {
        seekBarUpdater.run();
    }

    /**
     * Fired when the user touches the activity. Keeps the controls visible until the event ends.
     */
    @Override
    public boolean dispatchTouchEvent(@Nonnull MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                showControlsOverlay();
                break;

            case MotionEvent.ACTION_MOVE:
                cancelControlsOverlayHide();
                break;

        }

        return super.dispatchTouchEvent(event);
    }

    private final class SeekBarUpdater extends RepeatingTask {

        // How often the seek bar should be updated (in milliseconds)
        private static final int UPDATING_FREQUENCY = 250;

        public SeekBarUpdater() {
            super(UPDATING_FREQUENCY);
        }

        @Override
        protected void doWork() {
            int progress = (int) playerFragment.getPlaybackPosition();
            animateTo(progress);
        }

        private void animateTo(int progress) {
            int oldProgress = seekBar.getProgress();

            // Only animate if playback is progressing forwards, otherwise it's confusing
            if (oldProgress < progress) {
                ObjectAnimator animator =
                        ObjectAnimator.ofInt(seekBar, "progress", oldProgress, progress);

                animator.setDuration(UPDATING_FREQUENCY);
                animator.setInterpolator(new LinearInterpolator());

                animator.start();
            } else {
                seekBar.setProgress(progress);
            }
        }

    }

}
