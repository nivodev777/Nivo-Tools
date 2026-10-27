
/* Stateful ViewModel keeps the music meta and state across config
   changes while the service owns the actual player. */

package com.nivodev.nivotools.ui.music;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

public class MusicViewModel extends ViewModel {

    private final MutableLiveData<List<Song>> songs = new MutableLiveData<>();

    public LiveData<List<Song>> getSongs() {
        return songs;
    }

    public void setSongs(List<Song> value) {
        songs.setValue(value == null ? new ArrayList<Song>() : value);
    }
}
