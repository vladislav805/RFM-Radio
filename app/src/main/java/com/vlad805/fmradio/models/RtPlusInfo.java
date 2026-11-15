package com.vlad805.fmradio.models;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Immutable value object holding Radiotext+ (RT+) metadata.
 */
public final class RtPlusInfo implements Parcelable {
    public static final RtPlusInfo EMPTY = new RtPlusInfo("", "", "");

    private final String title;
    private final String artist;
    private final String album;

    public RtPlusInfo(
            final String title,
            final String artist,
            final String album
    ) {
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.album = album != null ? album : "";
    }

    public RtPlusInfo(
            final String title,
            final String artist
    ) {
        this(title, artist, "");
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public boolean isEmpty() {
        return title.isEmpty() && artist.isEmpty() && album.isEmpty();
    }

    /**
     * Formats artist, title, and album for UI and subtitle display ("Artist — Title (Album)").
     *
     * @return Formatted string, or single non-empty field, or empty string if all are empty.
     */
    public String getFormattedText() {
        final StringBuilder builder = new StringBuilder();
        if (!artist.isEmpty() && !title.isEmpty()) {
            builder.append(artist).append(" — ").append(title);
        } else if (!artist.isEmpty()) {
            builder.append(artist);
        } else {
            builder.append(title);
        }

        if (!album.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" (").append(album).append(")");
            } else {
                builder.append(album);
            }
        }

        return builder.toString();
    }

    RtPlusInfo(final Parcel in) {
        final String t = in.readString();
        final String a = in.readString();
        final String al = in.readString();
        title = t != null ? t : "";
        artist = a != null ? a : "";
        album = al != null ? al : "";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
    }

    public static final Creator<RtPlusInfo> CREATOR = new Creator<>() {
        @Override
        public RtPlusInfo createFromParcel(final Parcel in) {
            return new RtPlusInfo(in);
        }

        @Override
        public RtPlusInfo[] newArray(final int size) {
            return new RtPlusInfo[size];
        }
    };

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RtPlusInfo)) {
            return false;
        }
        final RtPlusInfo that = (RtPlusInfo) other;
        return title.equals(that.title) && artist.equals(that.artist) && album.equals(that.album);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, artist, album);
    }

    @Override
    public String toString() {
        return "RtPlusInfo{" +
                "title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", album='" + album + '\'' +
                '}';
    }
}
