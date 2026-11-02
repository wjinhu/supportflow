package dev.supportflow.assistant.knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** A product-name anchor is mandatory; operation words only rank evidence within that product. */
final class ProductKnowledgeSearch {
    private static final Pattern ANDROID = Pattern.compile("安卓|android");
    private static final Pattern WINDOWS = Pattern.compile("windows|微软");
    private static final String APPLE_MOBILE_TERMS = "苹果手机|苹果平板|iphone|ipad|(?<![a-z0-9])ios(?![a-z0-9])";
    private static final Pattern APPLE_MOBILE = Pattern.compile(APPLE_MOBILE_TERMS);
    private static final Pattern APPLE_MOBILE_EXCLUDED = Pattern.compile(
            "(?:不适用|不支持|不兼容|不用于)[^。；\\n]{0,12}(?:" + APPLE_MOBILE_TERMS + ")"
                    + "|(?:" + APPLE_MOBILE_TERMS + ")[^。；\\n]{0,6}(?:不支持|不兼容|不可用)");
    private static final Pattern CONNECT = Pattern.compile("连接|配对|连不上|断连|搜不到|找不到");
    private static final Pattern CLEAN = Pattern.compile("清洁|清理|擦拭|保养|清洗");
    private static final Pattern POWER = Pattern.compile("电量|电池|没电|供电");
    private static final Pattern TROUBLE = Pattern.compile("失败|连不上|断连|搜不到|找不到|异常");
    private static final Pattern FAST_PAIR = Pattern.compile("快速配对|fast\s*pair|弹窗");

    private ProductKnowledgeSearch() { }

    static List<KnowledgeMatch> retrieve(List<KnowledgeDocument> documents, String productName, String message) {
        String product = compact(productName);
        String question = KnowledgeRetriever.normalize(message);
        if (product.length() < 2) return List.of();
        return documents.stream()
                .filter(document -> compact(document.title()).contains(product)
                        || document.keywords().stream().anyMatch(keyword -> compact(keyword).contains(product))
                        || compact(document.content()).contains(product))
                .filter(document -> compatible(document, product, question))
                .map(document -> new KnowledgeMatch(document.sourceId(), document.version(), document.title(),
                        document.content(), score(document, question), List.of("verified-product:" + productName)))
                .sorted(Comparator.comparingDouble(KnowledgeMatch::score).reversed()
                        .thenComparing(KnowledgeMatch::sourceId))
                .limit(2).toList();
    }

    private static boolean compatible(KnowledgeDocument document, String product, String question) {
        String text = normalized(document);
        String metadata = KnowledgeRetriever.normalize(document.title() + " " + String.join(" ", document.keywords()));
        // Brand/model-specific operating steps must not become facts about an unnamed demo headset.
        if (text.contains("airpods") && !product.contains("airpods")) return false;
        if (FAST_PAIR.matcher(metadata).find() && !FAST_PAIR.matcher(question).find()) return false;
        if (ANDROID.matcher(question).find() && (WINDOWS.matcher(text).find() || APPLE_MOBILE.matcher(text).find())
                && !ANDROID.matcher(text).find()) return false;
        if (WINDOWS.matcher(question).find() && (ANDROID.matcher(text).find() || APPLE_MOBILE.matcher(text).find())
                && !WINDOWS.matcher(text).find()) return false;
        if (APPLE_MOBILE.matcher(question).find()
                && (!APPLE_MOBILE.matcher(text).find() || APPLE_MOBILE_EXCLUDED.matcher(text).find())) return false;
        if (question.contains("电脑") && !text.contains("电脑") && !WINDOWS.matcher(text).find()) return false;
        if (CLEAN.matcher(question).find()) return CLEAN.matcher(text).find();
        if (question.contains("充电")) return text.contains("充电");
        if (POWER.matcher(question).find()) return POWER.matcher(text).find();
        if (CONNECT.matcher(question).find()) return CONNECT.matcher(text).find();
        return true;
    }

    private static double score(KnowledgeDocument document, String question) {
        String metadata = KnowledgeRetriever.normalize(document.title() + " " + String.join(" ", document.keywords()));
        String text = normalized(document);
        double score = 1;
        for (String term : KnowledgeRetriever.tokenize(question)) {
            if (metadata.contains(term)) score += 3;
            else if (text.contains(term)) score += 1;
        }
        boolean specificOperation = CLEAN.matcher(question).find() || question.contains("充电")
                || POWER.matcher(question).find() || TROUBLE.matcher(question).find();
        // A generic "how to use it" starts with ordinary setup; failures and specialized features
        // only outrank it when the buyer actually asks about those conditions.
        if (!specificOperation && CONNECT.matcher(metadata).find()) score += 4;
        if (!TROUBLE.matcher(question).find() && TROUBLE.matcher(metadata).find()) score -= 4;
        return score;
    }

    private static String normalized(KnowledgeDocument document) {
        return KnowledgeRetriever.normalize(document.title() + " " + String.join(" ", document.keywords())
                + " " + document.content());
    }

    private static String compact(String text) {
        return KnowledgeRetriever.normalize(text).replaceAll("\\s+", "");
    }
}
