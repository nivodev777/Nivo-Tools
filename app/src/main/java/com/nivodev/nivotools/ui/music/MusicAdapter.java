
/* Music list grid adapter: one row per song, mini controls and
   selection mode support. Mutates nothing in the media store. */

package com.nivodev.nivotools.ui.music;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.ItemMusicBinding;
import com.nivodev.nivotools.util.AnimUtils;

import java.util.List;
import java.util.Locale;

public class MusicAdapter extends ListAdapter<Song, MusicAdapter.VH> {

    public interface Listener {
        void onPlay(Song song);
    }

    private static final DiffUtil.ItemCallback<Song> DIFF =
            new DiffUtil.ItemCallback<Song>() {
                @Override
                public boolean areItemsTheSame(@NonNull Song a, @NonNull Song b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Song a, @NonNull Song b) {
                    return a.id == b.id
                            && safe(a.title).equals(safe(b.title))
                            && safe(a.artist).equals(safe(b.artist))
                            && a.duration == b.duration;
                }
            };

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private final Listener listener;
    /** Optional long-press hook (delete / rename / info). */
    public java.util.function.Consumer<Song> longPress;

    /** Diff-tracked playing state: only touched rows rebind, not the whole list. */
    private long lastPlayingId = -1L;
    private boolean lastPlayingFlag = false;

    public MusicAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemMusicBinding binding = ItemMusicBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position));
    }

    /** Re-paints the highlight of the row currently streaming. */
    public void refreshPlayingState() {
        MusicPlayer player = MusicPlayer.get();
        Song cur = player.current();
        long id = cur != null ? cur.id : -1L;
        boolean playing = player.isPlaying();
        if (id == lastPlayingId && playing == lastPlayingFlag) {
            return;
        }
        List<Song> list = getCurrentList();
        int oldPos = -1;
        int newPos = -1;
        for (int i = 0; i < list.size(); i++) {
            long sid = list.get(i).id;
            if (oldPos < 0 && sid == lastPlayingId) {
                oldPos = i;
            }
            if (newPos < 0 && sid == id) {
                newPos = i;
            }
            if (oldPos >= 0 && newPos >= 0) {
                break;
            }
        }
        lastPlayingId = id;
        lastPlayingFlag = playing;
        if (oldPos >= 0) {
            notifyItemChanged(oldPos);
        }
        if (newPos >= 0 && newPos != oldPos) {
            notifyItemChanged(newPos);
        }
    }

    public static String format(long ms) {
        long total = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    class VH extends RecyclerView.ViewHolder {

        private final ItemMusicBinding binding;
        private final ImageView icon;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;
        private final ImageButton action;

        VH(ItemMusicBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            icon = binding.itemIcon;
            title = binding.musicItemTitle;
            artist = binding.musicItemArtist;
            duration = binding.musicItemDuration;
            action = binding.itemAction;
        }

        void bind(final Song song) {
            title.setText(song.title);
            artist.setText(song.artist);
            duration.setText(song.duration > 0 ? format(song.duration) : "");

            MusicPlayer player = MusicPlayer.get();
            Song current = player.current();
            boolean mine = current != null && current.id == song.id;
            boolean playing = mine && player.isPlaying();

            int playingColor = com.nivodev.nivotools.util.Accent.of(title.getContext());
            int normalColor = ContextCompat.getColor(title.getContext(), R.color.light_on_surface);
            int mutedColor = ContextCompat.getColor(title.getContext(), R.color.light_on_surface_variant);
            title.setTextColor(mine ? playingColor : normalColor);
            icon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_music);
            icon.setColorFilter(mine ? playingColor : mutedColor, android.graphics.PorterDuff.Mode.SRC_IN);
            duration.setTextColor(mine ? playingColor : mutedColor);

            action.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            action.setOnClickListener(v -> {
                AnimUtils.pressScale(v);
                if (listener != null) {
                    listener.onPlay(song);
                }
            });

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPlay(song);
                }
            });
            binding.getRoot().setOnLongClickListener(v -> {
                if (longPress != null) {
                    longPress.accept(song);
                    return true;
                }
                return false;
            });
            AnimUtils.pressScale(binding.getRoot());
        }
    }
}
