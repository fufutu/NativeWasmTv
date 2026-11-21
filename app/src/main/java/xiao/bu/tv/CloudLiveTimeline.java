package xiao.bu.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Turns "wall clock time since an anchor" into a sliding HLS window. */
final class CloudLiveTimeline {
    static final class Window {
        final List<CloudLiveApi.Segment> items;
        final int targetDuration;
        final long mediaSequence;

        Window(List<CloudLiveApi.Segment> items, int targetDuration, long mediaSequence) {
            this.items = items;
            this.targetDuration = targetDuration;
            this.mediaSequence = mediaSequence;
        }
    }

    static Window single(CloudLiveStore store, String videoId, double now, int windowSize,
            boolean reset) {
        CloudLiveApi.Segments segments = store.ensureSegments(videoId);
        if (segments == null || segments.items.isEmpty()) {
            return null;
        }
        List<CloudLiveApi.Segment> items = segments.items;
        int total = items.size();
        double totalDuration = totalDuration(items);
        if (totalDuration <= 0) {
            totalDuration = Math.max(1, total * 2.0);
        }
        double anchor = store.anchor(videoId, reset);
        double elapsed = Math.max(0, now - anchor);
        long loop = (long) (elapsed / totalDuration);
        double offset = elapsed - loop * totalDuration;
        int index = locateSegmentIndex(items, offset);
        long mediaSequence = loop * total + index;
        List<CloudLiveApi.Segment> window = new ArrayList<CloudLiveApi.Segment>(windowSize);
        for (int i = 0; i < windowSize; i++) {
            window.add(items.get((index + i) % total));
        }
        return new Window(window, realTargetDuration(items), mediaSequence);
    }

    static Window folder(CloudLiveStore store, Executor executor, String folderId,
            CloudLiveApi.FolderMeta meta, double now, int windowSize, boolean reset,
            int startEpisode) {
        List<CloudLiveApi.Episode> episodes = meta.episodes;
        if (episodes == null || episodes.isEmpty() || meta.totalDuration <= 0) {
            return null;
        }
        double anchor = store.anchor(folderId, reset);
        double elapsed = Math.max(0, now - anchor);
        long loop = (long) (elapsed / meta.totalDuration);
        double offset = elapsed - loop * meta.totalDuration;
        int episodeIndex = bisectRight(meta.offsets, offset) - 1;
        episodeIndex = Math.max(0, Math.min(episodeIndex, episodes.size() - 1));
        double localOffset = offset - meta.offsets[episodeIndex];
        if (startEpisode >= 0 && startEpisode < episodes.size()) {
            // Voice-directed season playback: start at the first segment of the
            // requested episode, then continue like a normal pseudo-live stream.
            episodeIndex = startEpisode;
            localOffset = 0;
        }

        CloudLiveApi.Episode current = episodes.get(episodeIndex);
        CloudLiveApi.Segments currentSegments = store.ensureSegments(current.fileId);
        if (currentSegments == null || currentSegments.items.isEmpty()) {
            return null;
        }
        List<CloudLiveApi.Segment> currentItems = currentSegments.items;
        int index = locateSegmentIndex(currentItems, localOffset);
        int targetDuration = realTargetDuration(currentItems);

        double averageSegment = current.duration > 0
                ? current.duration / currentItems.size() : 2.0;
        if (averageSegment <= 0) {
            averageSegment = 2.0;
        }
        int totalEstimated = 0;
        int offsetBeforeCurrent = 0;
        for (int i = 0; i < episodes.size(); i++) {
            CloudLiveApi.Episode episode = episodes.get(i);
            int count;
            if (i == episodeIndex) {
                count = currentItems.size();
            } else {
                CloudLiveApi.Segments cached = store.cachedSegments(episode.fileId);
                if (cached != null && !cached.items.isEmpty()) {
                    count = cached.items.size();
                } else if (episode.duration > 0) {
                    count = Math.max(1, (int) Math.round(episode.duration / averageSegment));
                } else {
                    count = 1;
                }
            }
            if (i < episodeIndex) {
                offsetBeforeCurrent += count;
            }
            totalEstimated += count;
        }
        long mediaSequence = loop * (long) totalEstimated + offsetBeforeCurrent + index;

        List<CloudLiveApi.Segment> window = new ArrayList<CloudLiveApi.Segment>(windowSize);
        int cursorEpisode = episodeIndex;
        int cursorSegment = index;
        int safety = 0;
        int maxIterations = episodes.size() + windowSize + 5;
        while (window.size() < windowSize && safety < maxIterations) {
            safety++;
            if (cursorEpisode >= episodes.size()) {
                cursorEpisode = 0;
            }
            List<CloudLiveApi.Segment> items;
            if (cursorEpisode == episodeIndex) {
                items = currentItems;
            } else {
                CloudLiveApi.Segments segments =
                        store.ensureSegments(episodes.get(cursorEpisode).fileId);
                if (segments == null || segments.items.isEmpty()) {
                    if (window.isEmpty()) {
                        return null;
                    }
                    break;
                }
                items = segments.items;
            }
            for (int i = cursorSegment; i < items.size(); i++) {
                window.add(items.get(i));
                if (window.size() >= windowSize) {
                    break;
                }
            }
            cursorEpisode++;
            cursorSegment = 0;
        }
        if (window.isEmpty()) {
            return null;
        }
        int nextEpisode = (episodeIndex + 1) % episodes.size();
        if (executor != null) {
            store.prefetchSegments(episodes.get(nextEpisode).fileId, executor);
        }
        return new Window(window, targetDuration, mediaSequence);
    }

    private static double totalDuration(List<CloudLiveApi.Segment> segments) {
        double total = 0;
        for (CloudLiveApi.Segment segment : segments) {
            total += segment.duration;
        }
        return total;
    }

    private static int locateSegmentIndex(List<CloudLiveApi.Segment> segments, double offset) {
        if (segments.isEmpty() || offset <= 0) {
            return 0;
        }
        double accumulated = 0;
        for (int i = 0; i < segments.size(); i++) {
            accumulated += segments.get(i).duration;
            if (offset < accumulated) {
                return i;
            }
        }
        return segments.size() - 1;
    }

    private static int realTargetDuration(List<CloudLiveApi.Segment> segments) {
        double max = 0;
        for (CloudLiveApi.Segment segment : segments) {
            if (segment.duration > max) {
                max = segment.duration;
            }
        }
        return Math.max(1, (int) Math.ceil(max));
    }

    private static int bisectRight(double[] values, double target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (target < values[middle]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
