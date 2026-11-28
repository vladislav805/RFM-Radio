package com.vlad805.fmradio.recordings;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RecyclerView adapter displaying subdirectories followed by audio recordings.
 */
public final class RecordingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface OnItemClickListener {
        void onDirectoryClick(@NonNull String dirName);

        void onDirectoryLongClick(@NonNull View anchor, @NonNull String dirName);

        void onRecordingClick(@NonNull RecordingItem item, int recordingIndex);

        void onRecordingLongClick(@NonNull View anchor, @NonNull RecordingItem item);
    }

    private static final int TYPE_DIRECTORY = 0;
    private static final int TYPE_RECORDING = 1;

    private final OnItemClickListener mListener;
    private final List<String> mDirectories = new ArrayList<>();
    private final Map<String, Integer> mDirectoryCounts = new HashMap<>();
    private final List<RecordingItem> mRecordings = new ArrayList<>();
    @Nullable
    private String mActiveRelativePath;

    public RecordingsAdapter(@NonNull final OnItemClickListener listener) {
        mListener = listener;
    }

    public void setListing(@NonNull final DirectoryListing listing) {
        mDirectories.clear();
        mDirectoryCounts.clear();
        for (final String dir : listing.getSubdirectories()) {
            mDirectories.add(dir);
            mDirectoryCounts.put(dir, listing.getSubdirectoryCount(dir));
        }

        mRecordings.clear();
        mRecordings.addAll(listing.getRecordings());
        notifyDataSetChanged();
    }

    public void updateRecording(final int recordingIndex, @NonNull final RecordingItem updated) {
        if (recordingIndex < 0 || recordingIndex >= mRecordings.size()) {
            return;
        }

        mRecordings.set(recordingIndex, updated);
        notifyItemChanged(mDirectories.size() + recordingIndex);
    }

    public void setActiveRelativePath(@Nullable final String relativePath) {
        mActiveRelativePath = relativePath;
        notifyDataSetChanged();
    }

    @NonNull
    public List<RecordingItem> getRecordings() {
        return mRecordings;
    }

    @Override
    public int getItemCount() {
        return mDirectories.size() + mRecordings.size();
    }

    @Override
    public int getItemViewType(final int position) {
        return position < mDirectories.size() ? TYPE_DIRECTORY : TYPE_RECORDING;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == TYPE_DIRECTORY) {
            return new DirectoryViewHolder(inflater.inflate(R.layout.item_recording_directory, parent, false));
        }

        return new RecordingViewHolder(inflater.inflate(R.layout.item_recording_file, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
        if (holder instanceof DirectoryViewHolder) {
            final String dirName = mDirectories.get(position);
            final Integer count = mDirectoryCounts.get(dirName);
            ((DirectoryViewHolder) holder).bind(dirName, count != null ? count : 0, mListener);
            return;
        }

        final int recIndex = position - mDirectories.size();
        final RecordingItem item = mRecordings.get(recIndex);
        final boolean isActive = item.getRelativePath().equals(mActiveRelativePath);
        ((RecordingViewHolder) holder).bind(item, recIndex, isActive, mListener);
    }

    private static final class DirectoryViewHolder extends RecyclerView.ViewHolder {
        private final TextView mName;
        private final TextView mCount;

        DirectoryViewHolder(@NonNull final View itemView) {
            super(itemView);
            mName = itemView.findViewById(R.id.dir_name);
            mCount = itemView.findViewById(R.id.dir_count);
        }

        void bind(
                @NonNull final String dirName,
                final int recordingCount,
                @NonNull final OnItemClickListener listener
        ) {
            mName.setText(dirName);
            mCount.setText(itemView.getResources().getQuantityString(
                    R.plurals.recordings_folder_count,
                    recordingCount,
                    recordingCount
            ));
            itemView.setOnClickListener(v -> listener.onDirectoryClick(dirName));
            itemView.setOnLongClickListener(v -> {
                listener.onDirectoryLongClick(v, dirName);
                return true;
            });
        }
    }

    private static final class RecordingViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mIcon;
        private final TextView mTitle;
        private final TextView mSubtitle;
        private final View mRdsBadge;

        RecordingViewHolder(@NonNull final View itemView) {
            super(itemView);
            mIcon = itemView.findViewById(R.id.recording_icon);
            mTitle = itemView.findViewById(R.id.recording_title);
            mSubtitle = itemView.findViewById(R.id.recording_subtitle);
            mRdsBadge = itemView.findViewById(R.id.recording_rds_badge);
        }

        void bind(
                @NonNull final RecordingItem item,
                final int recordingIndex,
                final boolean isActive,
                @NonNull final OnItemClickListener listener
        ) {
            final Context context = itemView.getContext();
            mTitle.setText(item.getDisplayName());
            mSubtitle.setText(formatSubtitle(item));
            mRdsBadge.setVisibility(item.hasRfmMetadata() ? View.VISIBLE : View.GONE);

            final int titleColor = MaterialColors.getColor(
                    mTitle,
                    isActive
                            ? androidx.appcompat.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOnSurface
            );
            final int iconTint = MaterialColors.getColor(
                    mIcon,
                    isActive
                            ? androidx.appcompat.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOnSurfaceVariant
            );
            mTitle.setTextColor(titleColor);
            mIcon.setColorFilter(iconTint);

            itemView.setOnClickListener(v -> listener.onRecordingClick(item, recordingIndex));
            itemView.setOnLongClickListener(v -> {
                listener.onRecordingLongClick(v, item);
                return true;
            });
        }

        @NonNull
        private static String formatSubtitle(@NonNull final RecordingItem item) {
            final StringBuilder sb = new StringBuilder();
            sb.append(item.getExtension().toUpperCase(Locale.US));

            if (item.getDurationMillis() > 0L) {
                sb.append(" · ");
                sb.append(Utils.getTimeStringBySeconds((int) (item.getDurationMillis() / 1000L)));
            }

            sb.append(" · ");
            sb.append(formatSize(item.getSizeBytes()));
            return sb.toString();
        }

        @NonNull
        private static String formatSize(final long bytes) {
            if (bytes >= 1024L * 1024L) {
                return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
            }

            return String.format(Locale.US, "%d KB", Math.max(1L, bytes / 1024L));
        }
    }
}
