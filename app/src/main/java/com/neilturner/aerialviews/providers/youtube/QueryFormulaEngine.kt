package com.neilturner.aerialviews.providers.youtube

import java.util.Calendar
import kotlin.random.Random
import timber.log.Timber

object QueryFormulaEngine {
    private const val TAG = "YouTubeQueries"
    private const val NATURE_QUERY_RATIO = 0.45f
    private const val SCENIC_QUERY_RATIO = 0.20f
    private const val MIN_NATURE_QUERY_COUNT = 12
    private const val MIN_SCENIC_QUERY_COUNT = 6

    enum class QueryCategory {
        AERIAL,
        NATURE,
    }

    private val qualifiers =
        listOf(
            "4K",
            "8K",
            "4K HDR",
            "cinematic 4K",
            "ultra HD",
        )

    private val shotTypes =
        listOf(
            "aerial drone",
            "drone footage",
            "aerial timelapse",
            "aerial flyover",
            "drone flythrough",
            "bird's eye view",
            "low altitude drone",
            "hyperlapse aerial",
        )

    private val subjects =
        listOf(
            "waterfall",
            "ocean waves",
            "river canyon",
            "coral reef",
            "glacier lake",
            "hot spring",
            "sea cliffs",
            "mangrove",
            "fjord",
            "frozen lake",
            "delta river",
            "lagoon",
            "mountain range",
            "volcanic crater",
            "sand dunes",
            "canyon",
            "valley",
            "plateau",
            "salt flats",
            "cave",
            "lava field",
            "limestone karst",
            "rice terraces",
            "forest",
            "rainforest",
            "bamboo forest",
            "cherry blossom",
            "lavender fields",
            "tulip fields",
            "autumn foliage",
            "wildfire smoke",
            "lightning storm",
            "fog valley",
            "savanna wildlife",
            "arctic tundra",
            "mangrove swamp",
            "city skyline",
            "ancient ruins",
            "coastal village",
            "mountain monastery",
            "floating market",
            "rooftop garden",
        )

    private val locations =
        listOf(
            "Iceland",
            "Norway",
            "Scotland",
            "Faroe Islands",
            "Dolomites Italy",
            "Swiss Alps",
            "Amalfi Coast Italy",
            "Santorini Greece",
            "Lofoten Norway",
            "Azores Portugal",
            "Plitvice Croatia",
            "Transylvania Romania",
            "Cappadocia Turkey",
            "Meteora Greece",
            "Bali Indonesia",
            "Ha Long Bay Vietnam",
            "Zhangjiajie China",
            "Guilin China",
            "Kyoto Japan",
            "Hokkaido Japan",
            "Raja Ampat Indonesia",
            "Coron Philippines",
            "Kerala India",
            "Ladakh India",
            "Bhutan Himalayas",
            "Mekong Delta Vietnam",
            "Patagonia Chile",
            "Amazon rainforest",
            "Antelope Canyon Arizona",
            "Banff Canada",
            "Alaska wilderness",
            "Zion Canyon Utah",
            "Angel Falls Venezuela",
            "Atacama Desert Chile",
            "Iguazu Falls Brazil",
            "Torres del Paine Chile",
            "Moab Utah",
            "Big Sur California",
            "Sahara Desert Morocco",
            "Serengeti Tanzania",
            "Victoria Falls Zimbabwe",
            "Namib Desert Namibia",
            "Okavango Delta Botswana",
            "Mount Kilimanjaro",
            "Wadi Rum Jordan",
            "Dead Sea",
            "Great Barrier Reef Australia",
            "New Zealand fjords",
            "Milford Sound New Zealand",
            "Whitsundays Australia",
            "Tasmania wilderness",
            "Antarctica",
            "Svalbard Norway",
            "Greenland",
            "Northern Lights Finland",
            "Arctic Ocean",
        )

    private val conditions =
        listOf(
            "sunrise",
            "golden hour sunset",
            "blue hour",
            "storm clouds",
            "fog mist",
            "snow winter",
            "autumn fall colors",
            "spring bloom",
            "night stars",
            "moonlight",
            "rain",
            "crystal clear",
            "4K no music",
            "ambient sounds only",
        )

    private val channels =
        listOf(
            "Jakob Owens aerial",
            "Drone Footage 4K",
            "Nature Relaxation Films",
            "4K Relaxation Channel",
            "Mavic Drone",
            "JSFILMZ aerial",
            "Expedia Viewfinder aerial",
            "LoungeV Films",
            "FreqFlyer",
            "Beautiful Destinations aerial",
        )

    private val seasonalQueries =
        mapOf(
            1 to listOf("4K aerial snow winter mountains", "4K arctic frozen landscape"),
            2 to listOf("4K aerial snow melt river", "4K winter aerial Nordic"),
            3 to listOf("4K aerial cherry blossom Japan", "4K spring bloom fields aerial"),
            4 to listOf("4K tulip fields aerial Netherlands", "4K spring waterfall aerial"),
            5 to listOf("4K aerial wildflower meadow", "4K lavender fields aerial Provence"),
            6 to listOf("4K aerial summer ocean beach", "4K aerial midnight sun Scandinavia"),
            7 to listOf("4K aerial tropical island summer", "4K aerial alpine meadow summer"),
            8 to listOf("4K aerial summer monsoon rainforest", "4K aerial coastal summer"),
            9 to listOf("4K aerial autumn foliage New England", "4K fall colors aerial canyon"),
            10 to listOf("4K aerial Halloween fog misty forest", "4K autumn aerial Japan"),
            11 to listOf("4K aerial late autumn bare forest", "4K aerial first snow mountains"),
            12 to listOf("4K aerial winter wonderland", "4K aerial northern lights aurora"),
        )

    val aiVideoBlacklist =
        listOf(
            "ai generated",
            "made with ai",
            "generated by ai",
            "created with ai",
            "rendered by ai",
            "sora",
            "runway ml",
            "runway",
            "stable diffusion",
            "midjourney video",
            "pika labs",
            "kling ai",
            "ai nature",
            "ai cinematic",
            "ai animation",
            "ai short film",
            "artificial intelligence video",
            "synthetically generated",
            "ai art video",
            "neural network video",
            "rendered with ai",
            "cgi",
            "cg animation",
            "3d animation",
            "3d render",
            "rendered",
            "procedural",
            "unreal engine",
            "blender render",
            "virtual scenery",
            "fantasy landscape",
            "dreamscape",
        )

    val bumperTitleBlacklist =
        listOf(
            "subscribe",
            "click here",
            "watch more",
            "full video",
            "part 1",
            "part 2",
            "episode",
            "compilation",
            "best of",
            "top 10",
            "reaction",
            "review",
            "unboxing",
            "vlog",
            "travel vlog",
            "travel diary",
            "travel guide",
            "travel film",
            "travel movie",
            "travel cinematic",
            "tour guide",
            "city tour",
            "hotel",
            "resort",
            "things to do",
            "itinerary",
            "road trip",
            "backpacking",
            "backpacker",
            "honeymoon",
            "vacation",
            "vacay",
            "tutorial",
            "how to",
            "wallpaper",
            "background video",
            "animated",
            "animation",
            "cgi",
            "3d",
            "render",
            "rendered",
            "loop",
        )

    val qualitySignals =
        listOf(
            "no music",
            "no talking",
            "slow motion",
            "8k",
            "4k hdr",
            "cinematic",
            "timelapse",
            "real footage",
            "documentary",
            "nature film",
            "wildlife",
        )

    private fun weeklySeed(): Long {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR).toLong() * 1000 + cal.get(Calendar.WEEK_OF_YEAR)
    }

    private fun dailySeed(): Long {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR).toLong() * 10_000L + cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun refreshSeed(): Long = System.currentTimeMillis() / (1000L * 60L * 60L * 6L)

    private fun weeklyRng(): Random = Random(weeklySeed())

    private fun dailyRng(): Random = Random(dailySeed())

    private fun refreshRng(): Random = Random(refreshSeed())

    fun generateQueryPool(count: Int = 25): List<String> {
        val resolvedCount = count.coerceAtLeast(1)
        val natureCount = (resolvedCount * NATURE_QUERY_RATIO).toInt().coerceAtLeast(MIN_NATURE_QUERY_COUNT).coerceAtMost(resolvedCount)
        val scenicCount =
            (resolvedCount * SCENIC_QUERY_RATIO)
                .toInt()
                .coerceAtLeast(MIN_SCENIC_QUERY_COUNT)
                .coerceAtMost((resolvedCount - natureCount).coerceAtLeast(0))
        val aerialCount = (resolvedCount - natureCount - scenicCount).coerceAtLeast(1)

        val natureQueries = buildNatureQueries(dailyRng(), natureCount)
        val scenicQueries = buildScenicQueries(weeklyRng(), scenicCount)
        val aerialQueries = buildAerialQueries(refreshRng(), aerialCount)
        val finalPool =
            (natureQueries + scenicQueries + aerialQueries)
                .distinct()
                .shuffled(Random(dailySeed() xor refreshSeed() xor weeklySeed()))
                .take(resolvedCount)
        Timber.tag(TAG).d("Generated %s YouTube search variants", finalPool.size)
        return finalPool
    }

    fun generateQueryPool(
        baseQuery: String,
        count: Int = 25,
        entropySeed: Long = 0L,
    ): List<String> {
        val basePool = generateQueryPool(count = count)
        val normalizedBaseQuery = baseQuery.trim()
        if (normalizedBaseQuery.isBlank()) {
            return basePool
        }

        val rng = Random(dailySeed() xor refreshSeed() xor entropySeed)
        return (listOf(normalizedBaseQuery) + basePool)
            .distinct()
            .shuffled(rng)
            .take(count)
    }

    fun generateFallbackQueryPool(
        baseQuery: String,
        count: Int = 12,
        entropySeed: Long = 0L,
    ): List<String> {
        val rng = Random(dailySeed() xor weeklySeed() xor refreshSeed() xor entropySeed xor 0x1F123BB5L)
        val basePool = generateQueryPool(count = (count * 3).coerceAtLeast(24))
        val normalizedBaseQuery = baseQuery.trim()

        val withVariants =
            if (normalizedBaseQuery.isBlank()) {
                basePool
            } else {
                listOf(
                    normalizedBaseQuery,
                    "$normalizedBaseQuery 4K aerial",
                    "$normalizedBaseQuery cinematic drone footage",
                ) + basePool
            }

        return withVariants
            .distinct()
            .shuffled(rng)
            .take(count)
    }

    fun categoryOf(query: String): QueryCategory =
        if (AERIAL_QUERY_REGEX.containsMatchIn(query)) {
            QueryCategory.AERIAL
        } else {
            QueryCategory.NATURE
        }

    fun totalPossibleCombinations(): Long =
        qualifiers.size.toLong() *
            shotTypes.size *
            subjects.size *
            locations.size *
            conditions.size

    fun freshnessSeed(baseQuery: String): String =
        "${baseQuery.trim()}|${weeklySeed()}|${dailySeed()}|${refreshSeed()}|${timeBucket()}"

    private fun buildNatureQueries(
        rng: Random,
        count: Int,
    ): List<String> {
        val generated = linkedSetOf<String>()

        repeat((count * 8).coerceAtLeast(24)) {
            generated +=
                buildString {
                    append(qualifiers.random(rng))
                    append(" ")
                    append(subjects.random(rng))
                    append(" ")
                    append(locations.random(rng))
                    if (rng.nextBoolean()) {
                        append(" ")
                        append(conditions.random(rng))
                    }
                }
            generated += "${subjects.random(rng)} ${locations.random(rng)} nature 4K"
        }

        return generated.shuffled(rng).take(count)
    }

    private fun buildScenicQueries(
        rng: Random,
        count: Int,
    ): List<String> {
        val generated = linkedSetOf<String>()
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1

        seasonalQueries[month]?.let { generated.addAll(it.shuffled(rng)) }
        generated.addAll(channels.shuffled(rng).take(count.coerceAtMost(3)))

        repeat((count * 6).coerceAtLeast(18)) {
            generated +=
                buildString {
                    append(qualifiers.random(rng))
                    append(" ")
                    append(subjects.random(rng))
                    append(" ")
                    append(locations.random(rng))
                    append(" ")
                    append(conditions.random(rng))
                }
        }

        return generated.shuffled(rng).take(count)
    }

    private fun buildAerialQueries(
        rng: Random,
        count: Int,
    ): List<String> {
        val generated = linkedSetOf<String>()

        repeat((count * 10).coerceAtLeast(32)) {
            generated +=
                buildString {
                    append(qualifiers.random(rng))
                    append(" ")
                    append(shotTypes.random(rng))
                    append(" ")
                    append(subjects.random(rng))
                    append(" ")
                    append(locations.random(rng))
                    if (rng.nextBoolean()) {
                        append(" ")
                        append(conditions.random(rng))
                    }
                }
        }

        generated.addAll(getTimeOfDayQueries(rng))
        return generated.shuffled(rng).take(count)
    }

    private fun getTimeOfDayQueries(rng: Random): List<String> =
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..9 ->
                listOf(
                    "4K aerial sunrise misty valley",
                    "4K morning fog forest aerial",
                    "4K sunrise golden hour mountains aerial",
                )

            in 10..16 ->
                listOf(
                    "4K aerial crystal clear ocean midday",
                    "4K sunny aerial tropical island",
                    "4K aerial clear sky mountains",
                )

            in 17..20 ->
                listOf(
                    "4K aerial golden hour sunset coast",
                    "4K sunset timelapse city aerial",
                    "4K dusk aerial ocean sunset",
                )

            else ->
                listOf(
                    "4K aerial city lights night",
                    "4K night sky milky way timelapse aerial",
                    "4K aurora borealis aerial time lapse",
                )
        }.shuffled(rng)

    private fun timeBucket(): String =
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..9 -> "morning"
            in 10..16 -> "day"
            in 17..20 -> "evening"
            else -> "night"
        }

    private val AERIAL_QUERY_REGEX =
        Regex(
            "(aerial|drone|bird's eye|flyover|flythrough|hyperlapse)",
            RegexOption.IGNORE_CASE,
        )
}
