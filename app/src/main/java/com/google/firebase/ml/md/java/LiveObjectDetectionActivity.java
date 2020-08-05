/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.ml.md.java;

import android.Manifest;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.firebase.ml.md.R;
import com.google.firebase.ml.md.java.camera.GraphicOverlay;
import com.google.firebase.ml.md.java.camera.WorkflowModel;
import com.google.firebase.ml.md.java.camera.WorkflowModel.WorkflowState;
import com.google.firebase.ml.md.java.camera.CameraSource;
import com.google.firebase.ml.md.java.camera.CameraSourcePreview;
import com.google.firebase.ml.md.java.objectdetection.DetectedObject;
import com.google.firebase.ml.md.java.objectdetection.MultiObjectProcessor;
import com.google.firebase.ml.md.java.objectdetection.ProminentObjectProcessor;
import com.google.firebase.ml.md.java.productsearch.BottomSheetScrimView;
import com.google.firebase.ml.md.java.productsearch.Product;
import com.google.firebase.ml.md.java.productsearch.ProductAdapter;
import com.google.firebase.ml.md.java.productsearch.SearchEngine;
import com.google.firebase.ml.md.java.productsearch.SearchedObject;
import com.google.firebase.ml.md.java.settings.PreferenceUtils;
import com.google.firebase.ml.md.java.settings.SettingsActivity;
import com.google.firebase.ml.vision.objects.FirebaseVisionObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates the object detection and visual search workflow using camera preview.
 */
public class LiveObjectDetectionActivity extends AppCompatActivity implements OnClickListener, Test {

    private static final String TAG = "LiveObjectActivity";

    private CameraSource cameraSource;
    private CameraSourcePreview preview;
    private GraphicOverlay graphicOverlay;
    private View settingsButton;
    private View flashButton;
    private Chip promptChip;
    private AnimatorSet promptChipAnimator;
    private ExtendedFloatingActionButton searchButton;
    private AnimatorSet searchButtonAnimator;
    private ProgressBar searchProgressBar;
    private WorkflowModel workflowModel;
    private WorkflowState currentWorkflowState;
    private SearchEngine searchEngine;

    private BottomSheetBehavior<View> bottomSheetBehavior;
    private BottomSheetScrimView bottomSheetScrimView;
    private RecyclerView productRecyclerView;
    private TextView bottomSheetTitleView;
    private Bitmap objectThumbnailForBottomSheet;
    private boolean slidingSheetUpFromHiddenState;

    public DetectedObject detectedObject;

    FrameLayout videoFrameLayout;
    VideoView videoView;
    Rect receivedRectange;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        searchEngine = new SearchEngine(getApplicationContext());

        setContentView(R.layout.activity_live_object);
        preview = findViewById(R.id.camera_preview);
        graphicOverlay = findViewById(R.id.camera_preview_graphic_overlay);
        graphicOverlay.setOnClickListener(this);
        cameraSource = new CameraSource(graphicOverlay);

        promptChip = findViewById(R.id.bottom_prompt_chip);
        promptChipAnimator =
                (AnimatorSet) AnimatorInflater.loadAnimator(this, R.animator.bottom_prompt_chip_enter);
        promptChipAnimator.setTarget(promptChip);

        searchButton = findViewById(R.id.product_search_button);
        searchButton.setOnClickListener(this);
        searchButtonAnimator =
                (AnimatorSet) AnimatorInflater.loadAnimator(this, R.animator.search_button_enter);
        searchButtonAnimator.setTarget(searchButton);

        searchProgressBar = findViewById(R.id.search_progress_bar);

        setUpBottomSheet();

        findViewById(R.id.close_button).setOnClickListener(this);
        flashButton = findViewById(R.id.flash_button);
        flashButton.setOnClickListener(this);
        settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(this);

        setUpWorkflowModel();

        /**
         *  CALLING VIDEO VIEW */
        initVideoFrame();


    }

    private void initVideoFrame() {

        videoFrameLayout = (FrameLayout) findViewById(R.id.static_overlay_container);

        videoView = new VideoView(this);
        //videoView.setVisibility(View.GONE);
        videoView.setVideoPath("android.resource://" + getPackageName() + "/" + R.raw.demo);

        MediaController mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 300);
        params.leftMargin = 20;
        params.rightMargin = 20;
        params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
        videoFrameLayout.addView(videoView, params);

    }

    @Override
    protected void onResume() {
        super.onResume();

        workflowModel.markCameraFrozen();
        settingsButton.setEnabled(true);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        currentWorkflowState = WorkflowState.NOT_STARTED;
        cameraSource.setFrameProcessor(
                PreferenceUtils.isMultipleObjectsMode(this)
                        ? new MultiObjectProcessor(graphicOverlay, workflowModel)
                        : new ProminentObjectProcessor(graphicOverlay, workflowModel));
        workflowModel.setWorkflowState(WorkflowState.DETECTING);
    }

    @Override
    protected void onPause() {
        super.onPause();
        currentWorkflowState = WorkflowState.NOT_STARTED;
        stopCameraPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraSource != null) {
            cameraSource.release();
            cameraSource = null;
        }
        searchEngine.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.product_search_button) {
            searchButton.setEnabled(false);
            workflowModel.onSearchButtonClicked();

        } else if (id == R.id.bottom_sheet_scrim_view) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        } else if (id == R.id.close_button) {
            onBackPressed();

        } else if (id == R.id.flash_button) {
            if (flashButton.isSelected()) {
                flashButton.setSelected(false);
                cameraSource.updateFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            } else {
                flashButton.setSelected(true);
                cameraSource.updateFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            }

        } else if (id == R.id.settings_button) {
            // Sets as disabled to prevent the user from clicking on it too fast.
            settingsButton.setEnabled(false);
            startActivity(new Intent(this, SettingsActivity.class));

        }
    }

    private void startCameraPreview() {
        if (!workflowModel.isCameraLive() && cameraSource != null) {
            try {
                workflowModel.markCameraLive();
                preview.start(cameraSource);
            } catch (IOException e) {
                Log.e(TAG, "Failed to start camera preview!", e);
                cameraSource.release();
                cameraSource = null;
            }
        }
    }

    private void stopCameraPreview() {
        if (workflowModel.isCameraLive()) {
            workflowModel.markCameraFrozen();
            flashButton.setSelected(false);
            preview.stop();
        }
    }

    private void setUpBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet));
        bottomSheetBehavior.setBottomSheetCallback(
                new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {
                        Log.d(TAG, "Bottom sheet new state: " + newState);
                        bottomSheetScrimView.setVisibility(
                                newState == BottomSheetBehavior.STATE_HIDDEN ? View.GONE : View.VISIBLE);
                        graphicOverlay.clear();

                        switch (newState) {
                            case BottomSheetBehavior.STATE_HIDDEN:
                                workflowModel.setWorkflowState(WorkflowState.DETECTING);
                                break;
                            case BottomSheetBehavior.STATE_COLLAPSED:
                            case BottomSheetBehavior.STATE_EXPANDED:
                            case BottomSheetBehavior.STATE_HALF_EXPANDED:
                                slidingSheetUpFromHiddenState = false;
                                break;
                            case BottomSheetBehavior.STATE_DRAGGING:
                            case BottomSheetBehavior.STATE_SETTLING:
                            default:
                                break;
                        }
                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                        SearchedObject searchedObject = workflowModel.searchedObject.getValue();
                        if (searchedObject == null || Float.isNaN(slideOffset)) {
                            return;
                        }

                        int collapsedStateHeight =
                                Math.min(bottomSheetBehavior.getPeekHeight(), bottomSheet.getHeight());
                        if (slidingSheetUpFromHiddenState) {
                            RectF thumbnailSrcRect =
                                    graphicOverlay.translateRect(searchedObject.getBoundingBox());
                            bottomSheetScrimView.updateWithThumbnailTranslateAndScale(
                                    objectThumbnailForBottomSheet,
                                    collapsedStateHeight,
                                    slideOffset,
                                    thumbnailSrcRect);

                        } else {
                            bottomSheetScrimView.updateWithThumbnailTranslate(
                                    objectThumbnailForBottomSheet, collapsedStateHeight, slideOffset, bottomSheet);
                        }
                    }
                });

        bottomSheetScrimView = findViewById(R.id.bottom_sheet_scrim_view);
        bottomSheetScrimView.setOnClickListener(this);

        bottomSheetTitleView = findViewById(R.id.bottom_sheet_title);
        productRecyclerView = findViewById(R.id.product_recycler_view);
        productRecyclerView.setHasFixedSize(true);
        productRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        productRecyclerView.setAdapter(new ProductAdapter(ImmutableList.of()));
    }

    /**
     * NIKHAR NOTES :
     * Rotating a device is one of a few configuration changes that an app can go through during its lifetime.
     * All of these configuration changes cause the Activity to be torn down and recreated.
     * <p>
     * The ViewModel class is designed to hold and manage UI-related data in a life-cycle conscious way.
     * This allows data to survive configuration changes such as screen rotations
     * <p>
     * The ViewModel exists from when the you first request a ViewModel
     * (usually in the onCreate the Activity)
     * until the Activity is finished and destroyed.
     * onCreate may be called several times during the life of an Activity,
     * such as when the app is rotated, but the ViewModel survives throughout.
     * <p>
     * <p>
     * The first time the ViewModelProviders.of method is called by MainActivity, it creates a new ViewModel instance.
     * When this method is called again, which happens whenever onCreate is called,
     * it will return the pre-existing ViewModel associated with the specific Court-Counter MainActivity
     * <p>
     * ViewModelProviders.of(<THIS ARGUMENT>).get(ScoreViewModel.class);
     * This allows you to have an app that opens a lot of different instances of the same Activity or Fragment,
     * but with different ViewModel information
     */
    private void setUpWorkflowModel() {
        workflowModel = ViewModelProviders.of(this).get(WorkflowModel.class);

        // Observes the workflow state changes, if happens, update the overlay view indicators and
        // camera preview state.

        /**
         * NIKHAR NOTES:
         * How live data works is, when the value of the livedata changes then it will be notified.
         * When you call setValue of the live data than the callback comes to the observer.
         *
         * songsLiveData.value = songsLiveData.value//this will give the callback to you observer        *
         *
         */

        workflowModel.workflowState.observe(
                this,
                workflowState -> {
                    /**currentWorkflowState = not started*/
                    if (workflowState == null || Objects.equal(currentWorkflowState, workflowState)) {
                        return;
                    }

                    currentWorkflowState = workflowState;
                    Log.d(TAG, "Current workflow state: " + currentWorkflowState.name());

                    if (PreferenceUtils.isAutoSearchEnabled(this)) {
                        stateChangeInAutoSearchMode(workflowState);
                    } else {
                        stateChangeInManualSearchMode(workflowState);
                    }
                });

        // Observes changes on the object to search, if happens, fire product search request.
        workflowModel.objectToSearch.observe(
                this, object -> searchEngine.search(object, workflowModel));


        // Observes changes on the object that has search completed, if happens, show the bottom sheet
        // to present search result.
        workflowModel.searchedObject.observe(
                this,
                searchedObject -> {
                    if (searchedObject != null) {
                        List<Product> productList = searchedObject.getProductList();
                        objectThumbnailForBottomSheet = searchedObject.getObjectThumbnail();
                        bottomSheetTitleView.setText(
                                getResources()
                                        .getQuantityString(
                                                R.plurals.bottom_sheet_title, productList.size(), productList.size()));
                        productRecyclerView.setAdapter(new ProductAdapter(productList));
                        slidingSheetUpFromHiddenState = true;
                        bottomSheetBehavior.setPeekHeight(preview.getHeight() / 2);
                        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                    }
                });
    }

    private void stateChangeInAutoSearchMode(WorkflowState workflowState) {
        boolean wasPromptChipGone = (promptChip.getVisibility() == View.GONE);

        searchButton.setVisibility(View.GONE);
        searchProgressBar.setVisibility(View.GONE);
        switch (workflowState) {
            case DETECTING:
            case DETECTED:
            case CONFIRMING:
                promptChip.setVisibility(View.VISIBLE);
                promptChip.setText(
                        workflowState == WorkflowState.CONFIRMING
                                ? R.string.prompt_hold_camera_steady
                                : R.string.prompt_point_at_an_object);
                startCameraPreview();
                break;
            case CONFIRMED:
                promptChip.setVisibility(View.VISIBLE);
                promptChip.setText(R.string.prompt_searching);
                stopCameraPreview();
                break;
            case SEARCHING:
                searchProgressBar.setVisibility(View.VISIBLE);
                promptChip.setVisibility(View.VISIBLE);
                promptChip.setText(R.string.prompt_searching);
                stopCameraPreview();
                break;
            case SEARCHED:
                promptChip.setVisibility(View.GONE);
                stopCameraPreview();
                break;
            default:
                promptChip.setVisibility(View.GONE);
                break;
        }

        boolean shouldPlayPromptChipEnteringAnimation =
                wasPromptChipGone && (promptChip.getVisibility() == View.VISIBLE);
        if (shouldPlayPromptChipEnteringAnimation && !promptChipAnimator.isRunning()) {
            promptChipAnimator.start();
        }
    }

    private void stateChangeInManualSearchMode(WorkflowState workflowState) {
        boolean wasPromptChipGone = (promptChip.getVisibility() == View.GONE);
        boolean wasSearchButtonGone = (searchButton.getVisibility() == View.GONE);


        searchProgressBar.setVisibility(View.GONE);
        switch (workflowState) {
            case DETECTING:

            case DETECTED:


            case CONFIRMING:
                videoView.setVisibility(View.GONE);
                if (videoView.isPlaying()) {
                    videoView.stopPlayback();
                }

                promptChip.setVisibility(View.VISIBLE);
                promptChip.setText(R.string.prompt_point_at_an_object);
                searchButton.setVisibility(View.GONE);
                startCameraPreview();
                break;
            case CONFIRMED:
                videoView.setVisibility(View.VISIBLE);

                if (!videoView.isPlaying()) {
                    videoView.start();
                }

                promptChip.setVisibility(View.GONE);
                searchButton.setVisibility(View.VISIBLE);
                searchButton.setEnabled(true);
                searchButton.setBackgroundColor(Color.WHITE);

                startCameraPreview();
                break;
            case SEARCHING:
                //videoFrameLayout.setVisibility(View.GONE);
                videoView.setVisibility(View.GONE);
                if (videoView.isPlaying()) {
                    videoView.stopPlayback();
                }

                promptChip.setVisibility(View.GONE);
                searchButton.setVisibility(View.VISIBLE);
                searchButton.setEnabled(false);
                searchButton.setBackgroundColor(Color.GRAY);
                searchProgressBar.setVisibility(View.VISIBLE);
                stopCameraPreview();
                break;
            case SEARCHED:
                videoView.setVisibility(View.GONE);
                if (videoView.isPlaying()) {
                    videoView.stopPlayback();
                }

                promptChip.setVisibility(View.GONE);
                searchButton.setVisibility(View.GONE);
                stopCameraPreview();
                break;
            default:
                videoView.setVisibility(View.GONE);
                if (videoView.isPlaying()) {
                    videoView.stopPlayback();
                }

                promptChip.setVisibility(View.GONE);
                searchButton.setVisibility(View.GONE);
                break;
        }

        boolean shouldPlayPromptChipEnteringAnimation =
                wasPromptChipGone && (promptChip.getVisibility() == View.VISIBLE);
        if (shouldPlayPromptChipEnteringAnimation && !promptChipAnimator.isRunning()) {
            promptChipAnimator.start();
        }

        boolean shouldPlaySearchButtonEnteringAnimation =
                wasSearchButtonGone && (searchButton.getVisibility() == View.VISIBLE);
        if (shouldPlaySearchButtonEnteringAnimation && !searchButtonAnimator.isRunning()) {
            searchButtonAnimator.start();
        }
    }

    @Override
    public void GetNotified(FirebaseVisionObject object) {
        receivedRectange = object.getBoundingBox();
        Log.d("coordinatesReceived", receivedRectange.centerX() + "//" + receivedRectange.centerY());

    }


}
