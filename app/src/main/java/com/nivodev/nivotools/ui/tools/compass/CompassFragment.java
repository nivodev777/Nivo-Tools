package com.nivodev.nivotools.ui.tools.compass;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentCompassBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

/**
 * Compass mini-app (rotating card + fixed needle - heading letter on top).
 *
 * == SENSOR FUSION CONTRACT (audited - root-cause fix documentation) ==
 *
 * The SensorManager.getRotationMatrix() contract:
 *   v_device = R * v_world   (R maps the world frame -> device frame)
 *   => v_world = R^T * v_device
 *
 *   DEVICE axis vectors in WORLD coordinates = COLUMNS of R
 *   (linear float[9]: col0 = idx 0,3,6 | col1 = idx 1,4,7 | col2 = idx 2,5,8).
 *   AOSP implementation proof (SensorManager.getRotationMatrix source):
 *     R[0..2] = H  = magnetic EAST axis in device coords
 *     R[3..5] = M  = magnetic NORTH axis in device coords
 *     R[6..8] = A  = UP axis in device coords
 *   => col1 (1,4,7) = device +Y (top edge) as world (E,N,U) components.
 *   Cross-check: the AOSP getOrientation azimuth = atan2(R[1], R[4]) equals
 *   our flat-branch bearing - phone flat with the top edge at east gives
 *   +90 degrees (pi/2), matching the javadoc "facing east = pi/2".
 *   Therefore: col1 = device +Y (screen TOP EDGE) in world (E,N,U),
 *              col2 = device +Z (screen normal)  in world (E,N,U).
 *
 *   World frame: X = east, Y = magnetic north, Z = up (ENU).
 *   Bearing (clockwise from north) of a world vector v (horizontal plane):
 *     bearing = atan2(v_east, v_north)
 *   0=N, 90=E, 180=S, 270=W (clockwise, compass convention).
 *
 *   ==== ROOT CAUSE OF THE OLD BUG ====
 *   The old code treated row1 (idx 3..5) as "device top edge in world",
 *   while it is actually the WORLD-north axis in DEVICE coordinates.
 *   Result: N and S kept coming out fine (they sit on the mirror axis),
 *   but E and W came out mirrored (270/90) - exactly what the user
 *   reported. FIX: use the columns (idx 1,4,7 / 2,5,8).
 *
 * POSTURE handling (hysteresis):
 *   FLAT (the screen normal has a large up component R[8]):
 *     top-edge bearing = device-Y column => (R[1], R[4], R[7])
 *   UPRIGHT (walking pose, top edge points at the sky = gimbal):
 *     the direction the user points with is the opposite of the screen
 *     normal (back camera) = -device Z => (-R[2], -R[5], -R[8])
 *   A branch flip would jump the needle: hysteresis thresholds 0.45/0.62.
 *
 * Numerical verification (device flat, by construction):
 *   Config A: top edge = EAST:
 *     deviceY(world) = (1,0,0) -> bearing atan2(1,0) = 90 => E
 *     deviceY(world) = (0,1,0) -> bearing 0               => N
 *   Config B: top edge = SOUTH: deviceY(world) = (0,-1,0) -> 180
 *   Config C: upright, back camera = EAST:
 *     deviceZ(world) = (-1,0,0) -> -col2 => (1,0,0) -> 90 => E
 *
 * STABILITY:
 *   - Circular-angle smoothing (wrap-aware), NO arithmetic averaging.
 *   - Deadband kills sensor micro-jitter (no move below 0.15 deg).
 *   - Magnetic health check: |B| norm outside 20..70 uT = unreliable ->
 *     calibration hint chip visible (interference: magnets, speakers,
 *     covers, metal tables, chargers).
 */
public class CompassFragment extends androidx.fragment.app.Fragment
        implements SensorEventListener {

    /** smoothing reactiveness (16ms loop) */
    private static final float FOLLOW = 0.18f;

    /** believable geomagnetic magnitude range in microtesla */
    private static final float MAG_MIN = 20f;
    private static final float MAG_MAX = 70f;

    private FragmentCompassBinding binding;

    private SensorManager sensors;
    private Sensor rotVec;
    private Sensor accel;
    private Sensor magnet;

    /** fallback combo sensor values */
    private final float[] gravity = new float[3];
    private final float[] magField = new float[3];
    private boolean hasAccel = false;
    private boolean hasMag = false;

    /** rotation matrix scratch buffer */
    private final float[] rotM = new float[9];

    /** freshly computed magnetic bearing (0..360) */
    private float magBearing = 0f;

    /** magnetic field health: false = readings are unreliable right now */
    private boolean magHealthy = true;

    /** last magnetometer timestamp where the health check ran */
    private long lastMagCheck = 0L;

    /** smoothed angle shown on screen (pulse-free and wrap-safe) */
    private float shown = 0f;

    /** loop that pulls shown toward the target every 16ms */
    private final android.os.Handler follow =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** posture flag - hysteresis so the branch never flickers */
    private boolean uprightPose = false;

    /** octant names - kept in sync with the dial POINTS array */
    private static final String[] POINTS =
            {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    // ------------------------------------------------------------ setup

    @Override
    public View onCreateView(@NonNull LayoutInflater in, ViewGroup c, Bundle s) {
        binding = FragmentCompassBinding.inflate(in, c, false);

        AnimUtils.slideFadeIn(binding.getRoot(), 0);

        sensors = (SensorManager) requireContext()
                .getSystemService(Context.SENSOR_SERVICE);
        boolean ok = sensors != null;
        if (ok) {
            rotVec = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnet = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            ok = rotVec != null || (accel != null && magnet != null);
        }
        if (ok) {
            binding.dial.setVisibility(View.VISIBLE);
            binding.dialHeadDeg.setVisibility(View.VISIBLE);
            binding.dialHeadPoint.setVisibility(View.VISIBLE);
            follow.post(walker);
        } else {
            // no sensors found - hide the dial + honest message (no fake data)
            binding.dial.setVisibility(View.GONE);
            binding.dialHeadDeg.setVisibility(View.GONE);
            binding.dialHeadPoint.setVisibility(View.GONE);
            binding.hintChip.setText(getString(R.string.compass_no_sensors));
            Haptics.action(requireActivity(), Haptics.TYPE_TICK);
        }
        return binding.getRoot();
    }

    // ------------------------------------------------------ sensor feed

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            // primary fusion path: quaternion -> rotation matrix
            SensorManager.getRotationMatrixFromVector(rotM, event.values);
            computeBearing();
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
            hasAccel = true;
            maybeCombo();
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            // health check: norm out of range = unreliable readings
            checkMagHealth(event.values);
            System.arraycopy(event.values, 0, magField, 0, 3);
            hasMag = true;
            maybeCombo();
        }
    }

    /** magnetometer health: |B| uT band check (interference detect) */
    private void checkMagHealth(float[] vals) {
        float x2 = vals[0] * vals[0];
        float y2 = vals[1] * vals[1];
        float z2 = vals[2] * vals[2];
        float norm = (float) Math.sqrt(x2 + y2 + z2);
        boolean ok = norm >= MAG_MIN && norm <= MAG_MAX;
        long now = android.os.SystemClock.elapsedRealtime();
        // max 1 update/sec - the chip must not flicker
        if (now - lastMagCheck < 1000L) {
            return;
        }
        lastMagCheck = now;
        if (ok != magHealthy) {
            magHealthy = ok;
            if (binding != null) {
                binding.hintChip.setVisibility(View.VISIBLE);
                binding.hintChip.setText(ok
                        ? getString(R.string.compass_hint)
                        : getString(R.string.compass_interference));
            }
        }
    }

    /** once accel+mag both arrived, build the matrix */
    private void maybeCombo() {
        if (rotVec != null || !hasAccel || !hasMag) {
            return;
        }
        if (SensorManager.getRotationMatrix(rotM, null, gravity, magField)) {
            computeBearing();
        }
    }

    /**
     * POSTURE-AWARE magnetic bearing (0..360):
     *   FLAT    -> top-edge column (R[1], R[4], R[7])
     *   UPRIGHT -> back camera (-R[2], -R[5], -R[8])
     * hysteresis branch-flag so it never flickers.
     */
    private void computeBearing() {
        float flatness = rotM[8];   // device-Z world-UP component (col2 = 2,5,8)
        if (flatness > 0.62f) {
            uprightPose = false;
        } else if (flatness < 0.45f) {
            uprightPose = true;
        }   // middle belt: keep the previous pose (anti-flicker)

        float ex, ny;
        if (uprightPose) {
            // walking pose: horizontal bearing of the back camera
            ex = -rotM[2];
            ny = -rotM[5];
        } else {
            // flat: top edge column
            ex = rotM[1];
            ny = rotM[4];
        }
        if (ex == 0f && ny == 0f) {
            return;   // degenerate - keep the previous value
        }
        float deg = (float) Math.toDegrees(Math.atan2(ex, ny));
        magBearing = ((deg % 360f) + 360f) % 360f;
    }

    /** magnetometer sick? figure-8 calibration hint */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (binding == null) {
            return;
        }
        if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                || accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            magHealthy = false;
            binding.hintChip.setVisibility(View.VISIBLE);
            binding.hintChip.setText(getString(R.string.compass_hint));
        }
    }

    // ---------------------------------------------------- smoothing anim

    /**
     * Circular smoothing: wrap-aware shortest-path delta, NO raw averaging
     * (at 359->1 deg the naive 180+180/2 would give 180; here it gives 2).
     * Deadband: micro-jitter stays invisible; FOLLOW is not too slow.
     */
    private final Runnable walker = new Runnable() {
        @Override
        public void run() {
            float want = magBearing;
            // shortest wrap delta: [-180, +180]
            float delta = ((want - shown % 360f) % 360f + 540f) % 360f - 180f;
            if (Math.abs(delta) > 0.15f) {
                shown += delta * FOLLOW;
            }
            // clamp running drift
            if (shown > 7200f || shown < -7200f) {
                shown = ((shown % 360f) + 360f) % 360f;
            }

            if (binding != null) {
                float norm = ((shown % 360f) + 360f) % 360f;
                binding.dial.setHeading(norm);
                int deg = Math.round(norm) % 360;
                binding.dialHeadDeg.setText(deg + "\u00B0");
                int octant = Math.round(norm / 45f) % 8;
                binding.dialHeadPoint.setText(POINTS[octant]);
            }
            follow.postDelayed(this, 16);
        }
    };

    // ------------------------------------------------------- lifecycle

    @Override
    public void onResume() {
        super.onResume();
        if (sensors != null) {
            if (rotVec != null) {
                sensors.registerListener(this, rotVec,
                        SensorManager.SENSOR_DELAY_GAME);
            }
            if (accel != null) {
                sensors.registerListener(this, accel,
                        SensorManager.SENSOR_DELAY_GAME);
            }
            if (magnet != null) {
                sensors.registerListener(this, magnet,
                        SensorManager.SENSOR_DELAY_GAME);
            }
            follow.removeCallbacks(walker);   // duplicate-listener guard
            follow.post(walker);
        }
    }

    @Override
    public void onPause() {
        // battery: release the sensors + loop
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        follow.removeCallbacks(walker);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        follow.removeCallbacks(walker);
        binding = null;
        super.onDestroyView();
    }
}
