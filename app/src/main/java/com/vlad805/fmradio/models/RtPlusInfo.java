package com.vlad805.fmradio.models;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable value object holding Radiotext+ (RT+) metadata.
 */
public final class RtPlusInfo implements Parcelable {
    public static final RtPlusInfo EMPTY = new RtPlusInfo(Collections.emptyMap());

    private final Map<String, String> fields;

    public RtPlusInfo(final Map<String, String> values) {
        final Map<String, String> populated = new LinkedHashMap<>();

        for (String key : RtPlusFields.names()) {
            final String value = values.get(key);

            if (value != null && !value.trim().isEmpty()) {
                populated.put(key, value.trim());
            }
        }

        fields = Collections.unmodifiableMap(populated);
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public String get(final String key) {
        final String value = fields.get(key);
        return value == null ? "" : value;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    private static Map<String, String> readFields(final Parcel in) {
        final Map<String, String> fields = new LinkedHashMap<>();
        final int count = in.readInt();

        for (int i = 0; i < count; ++i) {
            fields.put(in.readString(), in.readString());
        }

        return fields;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeInt(fields.size());

        for (Map.Entry<String, String> entry : fields.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeString(entry.getValue());
        }
    }

    public static final Creator<RtPlusInfo> CREATOR = new Creator<>() {
        @Override
        public RtPlusInfo createFromParcel(final Parcel in) {
            return new RtPlusInfo(readFields(in));
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
        return fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return fields.toString();
    }
}
