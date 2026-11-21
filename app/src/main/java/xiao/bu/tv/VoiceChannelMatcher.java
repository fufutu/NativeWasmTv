package xiao.bu.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves free-form speech text (already transcribed) to a channel in the
 * current catalog. Matching is tolerant of spoken fillers, Chinese numerals,
 * punctuation and common synonyms such as 照片 -> 影集.
 */
final class VoiceChannelMatcher {
    static final class Candidate {
        final int group;
        final int channel;
        final String groupName;
        final String channelName;
        final double score;

        Candidate(int group, int channel, String groupName, String channelName,
                double score) {
            this.group = group;
            this.channel = channel;
            this.groupName = groupName;
            this.channelName = channelName;
            this.score = score;
        }
    }

    static final class Result {
        final List<Candidate> candidates;

        Result(List<Candidate> candidates) {
            this.candidates = candidates;
        }

        boolean isEmpty() {
            return candidates.isEmpty();
        }

        Candidate best() {
            return candidates.isEmpty() ? null : candidates.get(0);
        }

        /** True when the top hit is strong enough to switch without asking. */
        boolean confident() {
            if (candidates.isEmpty() || candidates.get(0).score < 0.72) {
                return false;
            }
            return candidates.size() < 2
                    || candidates.get(0).score - candidates.get(1).score >= 0.12;
        }
    }

    private static final String[] FILLERS = {
            "请帮我", "帮我", "我想看", "我要看", "麻烦", "切换到", "切换", "换到",
            "调到", "转到", "打开", "播放", "开始", "一下", "看看", "节目", "频道",
            "谢谢", "吧", "呢", "啊", "请", "的"
    };

    private VoiceChannelMatcher() {
    }

    static Result match(String text, ChannelCatalog.Group[] groups) {
        String query = normalize(text);
        List<Candidate> candidates = new ArrayList<Candidate>();
        if (query.length() == 0 || groups == null) {
            return new Result(candidates);
        }
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            ChannelCatalog.Group group = groups[groupIndex];
            if (group == null || group.channels == null
                    || group.source == ChannelCatalog.SOURCE_FAVORITES
                    || (group.password != null && group.password.length() > 0)) {
                continue;
            }
            for (int channelIndex = 0; channelIndex < group.channels.length;
                    channelIndex++) {
                Channel channel = group.channels[channelIndex];
                if (channel == null || channel.name == null) {
                    continue;
                }
                String name = normalize(channel.name);
                if (name.length() == 0) {
                    continue;
                }
                double score = score(query, name, channel.name);
                if (score < 0.34) {
                    continue;
                }
                candidates.add(new Candidate(groupIndex, channelIndex, group.title,
                        channel.name, score));
            }
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override public int compare(Candidate left, Candidate right) {
                if (left.score == right.score) {
                    return left.channelName.length() - right.channelName.length();
                }
                return Double.compare(right.score, left.score);
            }
        });
        if (candidates.size() > 3) {
            return new Result(new ArrayList<Candidate>(candidates.subList(0, 3)));
        }
        return new Result(candidates);
    }

    private static double score(String query, String name, String rawName) {
        double score = 0.0;
        if (name.equals(query)) {
            score = 1.0;
        } else if (name.contains(query)) {
            score = 0.84 + 0.12 * Math.min(1.0, query.length() / (double) name.length());
        } else if (query.contains(name)) {
            score = 0.8;
        }
        double dice = dice(query, name);
        if (dice * 0.92 > score) {
            score = dice * 0.92;
        }
        double edit = editSimilarity(query, name);
        if (edit * 0.84 > score) {
            score = edit * 0.84;
        }
        // Numbers and years are usually the decisive part of a spoken request.
        String queryDigits = digits(query);
        if (queryDigits.length() > 0 && digits(name).contains(queryDigits)) {
            score += 0.06;
        }
        // Prefer rows whose visible name carries the spoken pattern over remakes.
        if (rawName != null && rawName.length() > 0 && query.length() >= 3
                && digits(rawName).contains(queryDigits) && queryDigits.length() > 0) {
            score += 0.01;
        }
        return Math.min(1.0, score);
    }

    private static String digits(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static double dice(String left, String right) {
        if (left.length() < 2 || right.length() < 2) {
            return 0.0;
        }
        Map<String, Integer> leftBigrams = bigrams(left);
        Map<String, Integer> rightBigrams = bigrams(right);
        int common = 0;
        for (Map.Entry<String, Integer> entry : leftBigrams.entrySet()) {
            Integer other = rightBigrams.get(entry.getKey());
            if (other != null) {
                common += Math.min(entry.getValue(), other.intValue());
            }
        }
        int total = (left.length() - 1) + (right.length() - 1);
        return total == 0 ? 0.0 : 2.0 * common / total;
    }

    private static Map<String, Integer> bigrams(String value) {
        Map<String, Integer> result = new HashMap<String, Integer>();
        for (int i = 0; i + 1 < value.length(); i++) {
            String gram = value.substring(i, i + 2);
            Integer previous = result.get(gram);
            result.put(gram, previous == null ? 1 : previous + 1);
        }
        return result;
    }

    private static double editSimilarity(String left, String right) {
        int max = Math.max(left.length(), right.length());
        if (max == 0) {
            return 0.0;
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - previous[right.length()] / (double) max;
    }

    /** Folds fillers, punctuation, numerals and common synonyms into one form. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.toLowerCase(java.util.Locale.US);
        value = value.replace("照片", "影集").replace("相册", "影集")
                .replace("图片", "影集").replace("图集", "影集");
        for (String filler : FILLERS) {
            value = value.replace(filler, "");
        }
        StringBuilder builder = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            char ch = value.charAt(index);
            if (isChineseNumeral(ch)) {
                int end = index;
                while (end < value.length() && (isChineseNumeral(value.charAt(end))
                        || value.charAt(end) == '百')) {
                    end++;
                }
                builder.append(chineseNumber(value.substring(index, end)));
                index = end;
                continue;
            }
            if ((ch >= '0' && ch <= '9')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '\u4e00' && ch <= '\u9fff')) {
                builder.append(ch);
            }
            index++;
        }
        return builder.toString();
    }

    private static boolean isChineseNumeral(char ch) {
        return "零〇一二两三四五六七八九十".indexOf(ch) >= 0;
    }

    private static String chineseNumber(String run) {
        int ten = run.indexOf('十');
        int hundred = run.indexOf('百');
        if (hundred >= 0) {
            int before = hundred == 0 ? 1 : digitValue(run.substring(0, hundred));
            int after = hundred + 1 >= run.length() ? 0
                    : parseTens(run.substring(hundred + 1));
            return String.valueOf(before * 100 + after);
        }
        if (ten >= 0) {
            int before = ten == 0 ? 1 : digitValue(run.substring(0, ten));
            int after = ten + 1 >= run.length() ? 0 : digitValue(run.substring(ten + 1));
            return String.valueOf(before * 10 + after);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < run.length(); i++) {
            builder.append(charDigit(run.charAt(i)));
        }
        return builder.toString();
    }

    private static int parseTens(String run) {
        int ten = run.indexOf('十');
        if (ten < 0) {
            return digitValue(run);
        }
        int before = ten == 0 ? 1 : digitValue(run.substring(0, ten));
        int after = ten + 1 >= run.length() ? 0 : digitValue(run.substring(ten + 1));
        return before * 10 + after;
    }

    private static int digitValue(String run) {
        int value = 0;
        for (int i = 0; i < run.length(); i++) {
            value = value * 10 + charDigit(run.charAt(i));
        }
        return value;
    }

    private static int charDigit(char ch) {
        switch (ch) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return 0;
        }
    }
}
