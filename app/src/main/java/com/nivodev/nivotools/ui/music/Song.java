
/* Immutable song entry (Parcelable) resolved from MediaStore. */

package com.nivodev.nivotools.ui.music;

import android.os.Parcel;
import android.os.Parcelable;

public class Song implements Parcelable {

    public final long id;
    public final String title;
    public final String artist;
    public final long duration;
    public final String name;
    public final long size;
    public final String path;

    public Song(long id, String title, String artist, long duration) {
        this(id, title, artist, duration, null, 0L, null);
    }

    public Song(long id, String title, String artist, long duration,
                String name, long size, String path) {
        this.id = id;
        this.title = title;
        this.artist = artist != null && !artist.trim().isEmpty() ? artist : "Unknown artist";
        this.duration = duration;
        this.name = name;
        this.size = size;
        this.path = path;
    }

    protected Song(Parcel in) {
        id = in.readLong();
        title = in.readString();
        artist = in.readString();
        duration = in.readLong();
        name = in.readString();
        size = in.readLong();
        path = in.readString();
    }

    public static final Creator<Song> CREATOR = new Creator<Song>() {
        @Override
        public Song createFromParcel(Parcel in) {
            return new Song(in);
        }

        @Override
        public Song[] newArray(int size) {
            return new Song[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeLong(duration);
        dest.writeString(name);
        dest.writeLong(size);
        dest.writeString(path);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
