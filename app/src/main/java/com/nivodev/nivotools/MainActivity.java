
/* Single-activity host: drawer + toolbar, destination routing,
   toolbar menu changes per screen and the shared glass backdrop. */

package com.nivodev.nivotools;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import android.content.res.ColorStateList;
import androidx.core.content.ContextCompat;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import com.nivodev.nivotools.databinding.ActivityMainBinding;
import com.nivodev.nivotools.ui.music.MusicFragment;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration mAppBarConfiguration;
    private ActivityMainBinding binding;
    private Menu toolbarMenu;

    /**
     * Legacy storage grant path (Android 10 and below): the launcher must
     * be a FIELD so it registers before any resume (registering inside a
     * click callback throws IllegalStateException).
     */
    private final androidx.activity.result.ActivityResultLauncher<String> readPerm =
            registerForActivityResult(
                    new androidx.activity.result.contract
                            .ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            // approved - no extra UI needed, reader works directly
                        } else {
                            // user declined - remind via the styled dialog next launch
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // whole-app random accent (music, calculator, notes, settings, alarm...)
        com.nivodev.nivotools.util.Accent.apply(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        // Drawer's own liquid backdrop is decorative only - the engine keeps the full-screen one
        binding.drawerLiquid.setRegisterWithEngine(false);
        setContentView(binding.getRoot());

        // Storage gate: check whether the permission is already allowed
        // (no dialog at all while it stays granted)
        checkStorageGate();

        // Keep content clear of the system bars (edge-to-edge is enforced on API 35+)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarMain.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        setSupportActionBar(binding.appBarMain.toolbar);

        DrawerLayout drawer = binding.drawerLayout;
        NavigationView navigationView = binding.navView;

        float corner = 26f * getResources().getDisplayMetrics().density;
        ShapeAppearanceModel shape = ShapeAppearanceModel.builder()
                .setTopRightCornerSize(corner)
                .setBottomRightCornerSize(corner)
                .build();
        MaterialShapeDrawable drawerBg = new MaterialShapeDrawable(shape);
        drawerBg.setFillColor(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.glass_drawer_light)));
        navigationView.setBackground(drawerBg);
        navigationView.setClipToOutline(true);

        // Drawer covers the full screen height; status-bar clearance goes to the header only
        final View headerView = navigationView.getHeaderView(0);
        ViewCompat.setOnApplyWindowInsetsListener(navigationView, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            headerView.setPadding(headerView.getPaddingLeft(), bars.top,
                    headerView.getPaddingRight(), headerView.getPaddingBottom());
            return insets;
        });

        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
        });

        binding.btnDrawerSettings.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            drawer.closeDrawer(GravityCompat.START);
            Navigation.findNavController(MainActivity.this, R.id.nav_host_fragment_content_main)
                    .navigate(R.id.nav_slideshow);
        });

        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_music, R.id.nav_calculator, R.id.nav_alarm, R.id.nav_notes,
                R.id.nav_reader, R.id.nav_stopwatch, R.id.nav_compass)
                .setOpenableLayout(drawer)
                .build();

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);

        // Auto-minimize the drawer whenever navigation reaches any screen,
        // and keep the search action visible only on the music screen
        navController.addOnDestinationChangedListener((ctlr, dest, args) -> {
            if (drawer.isDrawerOpen(GravityCompat.START)) {
                drawer.closeDrawer(GravityCompat.START);
            }
            if (toolbarMenu != null) {
                boolean onMusic = dest.getId() == R.id.nav_music;
                MenuItem search = toolbarMenu.findItem(R.id.action_search);
                if (search != null) {
                    search.setVisible(onMusic);
                }
                MenuItem reScan = toolbarMenu.findItem(R.id.action_refresh);
                if (reScan != null) {
                    reScan.setVisible(onMusic);
                }
                MenuItem sort = toolbarMenu.findItem(R.id.action_sort);
                if (sort != null) {
                    sort.setVisible(onMusic);
                    if (onMusic) {
                        markCurrentSort(toolbarMenu);
                    }
                }
                MenuItem sleep = toolbarMenu.findItem(R.id.action_sleep);
                if (sleep != null) {
                    sleep.setVisible(onMusic);
                }
            }
        });
    }


    /**
     * Storage permission state check - ONLY standard runtime permissions
     * are requested (no "All files access"), so Play Protect keeps the
     * app on the trusted side at install time:
     *   - Android 13+ (API 33): READ_MEDIA_AUDIO
     *   - Android 12L and below: READ_EXTERNAL_STORAGE
     * Reader documents flow through SAF and need no permission at all.
     */
    private boolean hasStorage() {
        boolean newStyle =
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU;
        String perm = newStyle
                ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        try {
            return ContextCompat.checkSelfPermission(this, perm)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** gate at launch: granted = continue silently, missing = styled prompt */
    private void checkStorageGate() {
        if (hasStorage()) {
            return;   // already allowed - no dialog, no nagging
        }
        try {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    this, R.style.ThemeOverlay_Nivo_AlertDialog)
                    .setTitle(R.string.perm_storage_title)
                    .setMessage(R.string.perm_storage_msg)
                    .setPositiveButton(R.string.perm_allow, (d, w) -> {
                        d.dismiss();
                        requestStorage();
                    })
                    .setNegativeButton(R.string.perm_later, null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** user pressed Allow - standard runtime popup (no Settings jump) */
    private void requestStorage() {
        try {
            String perm = android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.TIRAMISU
                    ? android.Manifest.permission.READ_MEDIA_AUDIO
                    : android.Manifest.permission.READ_EXTERNAL_STORAGE;
            readPerm.launch(perm);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        toolbarMenu = menu;
        MenuItem search = menu.findItem(R.id.action_search);
        MenuItem reScan = menu.findItem(R.id.action_refresh);
        boolean onMusic = false;
        try {
            NavController nc = Navigation.findNavController(this,
                    R.id.nav_host_fragment_content_main);
            onMusic = nc.getCurrentDestination() != null
                    && nc.getCurrentDestination().getId() == R.id.nav_music;
        } catch (Throwable ignored) {
        }
        if (reScan != null) {
            reScan.setVisible(onMusic);
        }
        if (search != null) {
            search.setVisible(onMusic);
        }
        MenuItem sort = menu.findItem(R.id.action_sort);
        if (sort != null) {
            sort.setVisible(onMusic);
            markCurrentSort(menu);
        }
        MenuItem sleep = menu.findItem(R.id.action_sleep);
        if (sleep != null) {
            sleep.setVisible(onMusic);
        }
        return true;
    }

    /** Single robust lookup for the currently live music screen. */
    private MusicFragment findMusicFragment() {
        androidx.navigation.fragment.NavHostFragment host =
                (androidx.navigation.fragment.NavHostFragment)
                        getSupportFragmentManager()
                                .findFragmentById(R.id.nav_host_fragment_content_main);
        if (host == null) {
            return null;
        }
        for (androidx.fragment.app.Fragment f :
                host.getChildFragmentManager().getFragments()) {
            if (f instanceof MusicFragment) {
                return (MusicFragment) f;
            }
        }
        return null;
    }

    /** Pre-check the sort radio that matches the persisted sort mode. */
    private void markCurrentSort(Menu menu) {
        MenuItem sort = menu.findItem(R.id.action_sort);
        if (sort == null || sort.getSubMenu() == null) {
            return;
        }
        int mode = com.nivodev.nivotools.ui.music.MusicStore
                .getSortMode(getApplicationContext());
        int[] ids = {R.id.action_sort_title, R.id.action_sort_artist,
                R.id.action_sort_duration, R.id.action_sort_date,
                R.id.action_sort_file};
        int pick = Math.max(0, Math.min(ids.length - 1, mode));
        for (int i = 0; i < ids.length; i++) {
            MenuItem it = menu.findItem(ids[i]);
            if (it != null) {
                it.setChecked(i == pick);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            MusicFragment mf = findMusicFragment();
            if (mf != null) {
                mf.loadSongs();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_sleep) {
            MusicFragment mf = findMusicFragment();
            if (mf != null) {
                mf.showSleepSheet();
            }
            return true;
        }
        Integer sortMode = matchSortMode(item.getItemId());
        if (sortMode != null) {
            item.setChecked(true);
            com.nivodev.nivotools.ui.music.MusicStore
                    .setSortMode(getApplicationContext(), sortMode);
            MusicFragment mf = findMusicFragment();
            if (mf != null) {
                mf.loadSongs();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_search) {
            androidx.navigation.fragment.NavHostFragment host =
                    (androidx.navigation.fragment.NavHostFragment)
                            getSupportFragmentManager()
                                    .findFragmentById(R.id.nav_host_fragment_content_main);
            if (host != null
                    && host.getChildFragmentManager().getPrimaryNavigationFragment()
                    instanceof MusicFragment) {
                ((MusicFragment) host.getChildFragmentManager()
                        .getPrimaryNavigationFragment()).openSearch();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_about) {
            new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Nivo_AlertDialog)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_desc)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Map a sort menu id to MusicStore mode, or null for other ids.
     * R.id fields are non-final here, so switch-on-id is not allowed. */
    private Integer matchSortMode(int id) {
        if (id == R.id.action_sort_title) {
            return com.nivodev.nivotools.ui.music.MusicStore.SORT_TITLE;
        }
        if (id == R.id.action_sort_artist) {
            return com.nivodev.nivotools.ui.music.MusicStore.SORT_ARTIST;
        }
        if (id == R.id.action_sort_duration) {
            return com.nivodev.nivotools.ui.music.MusicStore.SORT_DURATION;
        }
        if (id == R.id.action_sort_date) {
            return com.nivodev.nivotools.ui.music.MusicStore.SORT_DATE_ADDED;
        }
        if (id == R.id.action_sort_file) {
            return com.nivodev.nivotools.ui.music.MusicStore.SORT_FILE;
        }
        return null;
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }
}
