package app.subsonar.domain.recurring

import app.subsonar.data.Category

/** Word lists used by the recurring-charge detector (multilingual: en, no/da/sv, de, nl, fr, es, it, pt, pl, fi, cs, hu). */
internal object Vocabulary {
    /** Boilerplate words that carry no merchant identity. */
    val NOISE = setOf(
        "KORTKJOP", "KJOP", "KORTKOB", "KORTKOEB", "KOEB", "VARUKOP", "KORTKAUF", "KARTENZAHLUNG", "KARTENKAUF", "LASTSCHRIFT", "SEPA", "DIRECT",
        "DEBIT", "DD", "REF", "REFERENCE", "PAYMENT", "BETALING", "BETALNING", "ZAHLUNG", "PAGO", "PAIEMENT", "PAGAMENTO", "PLATBA", "CARD", "VISA",
        "MASTERCARD", "POS", "NETS", "AVTALEGIRO", "EFTPOS", "PAYPAL", "PURCHASE", "ONLINE", "THE", "AND", "OG", "UND", "DE", "DER", "DIE", "DAS", "LE", "LA",
        "EL", "DEL", "VIA", "FROM", "TO", "TIL", "FRA", "AN", "VON", "ABBUCHUNG", "ELV", "GIRO", "OVERFORSEL", "OVERFOERSEL", "TILISIIRTO", "CONTACTLESS",
        "KONTAKTLOS", "APPLE", "PAY", "GOOGLE", "TRANSACTION", "TRANSAKSJON", "NR", "NO", "NUMBER", "ID", "KORT", "KARTE", "TARJETA", "CARTE", "KARTA",
        "SQ", "SP", "IZ", "TST", "ORDER", "BILLING", "RECURRING",
    ).minus(setOf("APPLE", "GOOGLE")) // Apple/Google are services in their own right; "APPLE PAY" is removed as a bigram

    /** Known subscription brands (lower-case; matched on whole tokens or the joined key prefix). */
    val KNOWN_SERVICES = setOf(
        "netflix", "spotify", "disney", "disneyplus", "hbo", "hbomax", "viaplay", "youtube", "youtubepremium", "primevideo", "amazonprime", "audible", "dazn",
        "tidal", "deezer", "storytel", "nextory", "bookbeat", "audiobooks", "appletv", "apple", "icloud", "itunes", "google", "googleone", "microsoft", "office365",
        "microsoft365", "xbox", "playstation", "psn", "nintendo", "steam", "twitch", "adobe", "dropbox", "notion", "openai", "chatgpt", "anthropic", "github", "gitlab",
        "nordvpn", "expressvpn", "surfshark", "protonvpn", "proton", "1password", "lastpass", "bitwarden", "canva", "figma", "duolingo", "headspace", "calm", "strava",
        "patreon", "substack", "medium", "nytimes", "wsj", "economist", "spiegel", "zeit", "bild", "aftenposten", "dagbladet", "vg", "tv2", "tv2play", "nrk",
        "discovery", "paramount", "skyshowtime", "canal", "canalplus", "crunchyroll", "mubi", "plex", "evernote", "todoist", "grammarly", "zoom", "slack", "linkedin",
        "tinder", "bumble", "hinge", "peloton", "fitbit", "myfitnesspal", "sats", "evo", "fitness24seven", "anytimefitness", "basicfit", "puregym", "mcfit", "fitx",
        "telenor", "telia", "tele2", "talkmore", "ice", "vodafone", "orange", "sfr", "bouygues", "ziggo", "kpn", "odido", "o2", "three", "ee", "sky", "virgin",
        "movistar", "tim", "wind", "iliad", "freenet", "telekom", "1und1", "congstar", "fjordkraft", "tibber", "lyse", "hafslund", "audiobook", "nordnet",
        "prime", "spotifyab", "hulu", "peacock", "max", "mullvad", "backblaze", "icedrive", "pcloud", "mega", "idrive", "onedrive", "gdrive",
        "midjourney", "perplexity", "cursor", "jetbrains", "unity", "unreal", "ea", "ubisoft", "gamepass", "gamersgate", "humblebundle", "tinkoff", "kindle",
        "scribd", "blinkist", "brilliant", "coursera", "udemy", "skillshare", "masterclass", "babbel", "rosettastone", "lingoda",
    )

    /** Strong "this is a subscription" words. */
    val SUBSCRIPTION_KEYWORDS = setOf(
        "subscription", "subscr", "subscribe", "abonnement", "abonnemang", "abonnoment", "abo", "abonnent", "membership", "member", "medlemskap",
        "medlemskab", "medlemsavgift", "medlemsskab", "mitgliedschaft", "mitgliedsbeitrag", "lidmaatschap", "cotisation", "suscripcion", "cuota",
        "abbonamento", "assinatura", "subskrypcja", "abonament", "tilaus", "predplatne", "elofizetes", "premium", "annual", "yearly", "arlig", "arsabonnement",
        "jahresabo", "jaarlijks", "annuel", "anual", "annuale", "rocznie", "plus", "pro", "unlimited", "streaming", "playlist",
    )

    /** Words weaker than [SUBSCRIPTION_KEYWORDS] (very generic). */
    val WEAK_KEYWORDS = setOf("plus", "pro", "member", "unlimited", "online", "plan", "app", "club", "cloud", "vpn", "music", "tv", "news", "daily", "gym", "fitness", "mobile", "storage", "trial")

    /** Words that mean the cycle is yearly / quarterly / monthly. */
    val YEARLY_WORDS = setOf("annual", "yearly", "year", "arlig", "ar", "aar", "jahres", "jahresabo", "jaarlijks", "annuel", "anual", "annuale", "rocznie", "annualplan")
    val QUARTERLY_WORDS = setOf("quarterly", "kvartal", "kvartalsvis", "vierteljahr", "quartalsweise", "trimestral", "trimestre", "trimestrale", "kwartaal")
    val MONTHLY_WORDS = setOf("monthly", "manedlig", "maanedlig", "manadsvis", "monatlich", "maandelijks", "mensuel", "mensual", "mensile", "miesiecznie", "kuukausi")

    /** Words that mark a bill (mortgage, rent, loan, insurance, utilities) rather than a subscription. */
    val BILL_KEYWORDS = setOf(
        "mortgage", "rent", "rental", "loan", "boliglan", "husleie", "husleje", "hyra", "hyre", "bolig", "housing", "miete", "kaltmiete", "hypothek", "darlehen",
        "kredit", "hypotheek", "huur", "lening", "loyer", "hipoteca", "alquiler", "prestamo", "mutuo", "affitto", "emprestimo", "aluguel",
        "kredyt", "czynsz", "vuokra", "laina", "najem", "insurance", "forsikring", "forsakring", "versicherung", "verzekering", "assurance", "seguro", "assicurazione",
        "ubezpieczenie", "vakuutus", "pojisteni", "electricity", "strom", "stroem", "elektricitet", "elektrizitat", "energy", "energi", "vann", "vand",
        "wasser", "skatt", "skat", "steuer", "belasting", "impot", "impuesto", "tassa", "podatek", "daycare", "barnehage", "sfo", "kindergarten",
        "tuition", "studielan", "lanekassen", "csn", "fagforening", "a-kasse", "invoice", "faktura", "rechnung", "factuur", "facture", "factura", "fattura",
        "nedbetaling", "avdrag", "instalment", "installment", "afbetaling", "tilbakebetaling",
    )

    val AMBIGUOUS_BILL_WORDS = setOf("invoice", "faktura", "rechnung", "factuur", "facture", "factura", "fattura", "gas", "water", "tax", "lan", "union", "interest")

    /** Stores, fuel stations and restaurants (whole tokens or token suffixes). */
    val STORE_TOKENS = setOf(
        "rema", "kiwi", "coop", "extra", "meny", "spar", "bunnpris", "joker", "ica", "willys", "hemkop", "lidl", "aldi", "netto", "fotex", "bilka", "rewe", "edeka",
        "penny", "kaufland", "carrefour", "auchan", "leclerc", "intermarche", "monoprix", "mercadona", "dia", "eroski", "esselunga", "conad", "jumbo", "albert", "heijn",
        "tesco", "sainsburys", "sainsbury", "asda", "morrisons", "waitrose", "biedronka", "zabka", "circle", "shell", "esso", "bp", "statoil", "unox", "yx", "preem", "okq8", "aral",
        "mcdonalds", "mcdonald", "burger", "kfc", "subway", "starbucks", "dominos", "pizza", "cafe", "kafe", "kaffe", "coffee", "restaurant", "restaurang", "bakery", "bakeri",
        "backerei", "boulangerie", "kiosk", "narvesen", "pressbyran", "apotek", "pharmacy", "apotheke", "ikea", "hm", "zara", "supermarket", "supermarkt", "supermercado",
        "grocery", "mart", "market", "store", "shop", "bar", "pub", "grill", "kebab", "sushi", "taxi", "uber", "bolt", "parking", "parkering", "fuel", "petrol", "tankstelle",
        "bensin", "diesel", "hotel", "bistro", "deli", "butik", "basar", "bazaar", "pharma",
    )

    private val CATEGORY_WORDS: List<Pair<Category, Set<String>>> = listOf(
        Category.STREAMING to setOf("netflix", "hbo", "disney", "viaplay", "youtube", "primevideo", "dazn", "tv", "tv2", "streamflix", "paramount", "skyshowtime", "crunchyroll", "mubi", "hulu", "discovery", "video"),
        Category.MUSIC to setOf("spotify", "tidal", "deezer", "music", "musicbox", "storytel", "audible", "bookbeat", "nextory", "audiobook", "audiobooks"),
        Category.FITNESS to setOf("gym", "fitness", "sats", "evo", "basicfit", "puregym", "mcfit", "fitx", "anytimefitness", "peloton", "strava", "yoga", "training", "fitness24seven"),
        Category.PHONE_INTERNET to setOf("telco", "mobile", "telia", "telenor", "tele2", "talkmore", "vodafone", "internet", "fiber", "broadband", "ice", "o2", "orange", "telekom", "phone", "mobil"),
        Category.INSURANCE to setOf("insurance", "forsikring", "forsakring", "versicherung", "verzekering", "assurance", "seguro", "assicurazione"),
        Category.CLOUD to setOf("icloud", "dropbox", "cloud", "cloudstore", "onedrive", "googleone", "drive", "backblaze", "pcloud", "storage"),
        Category.SOFTWARE to setOf("adobe", "microsoft", "office365", "microsoft365", "github", "jetbrains", "notion", "openai", "chatgpt", "app", "focusapp", "vpn", "nordvpn", "vpnguard", "1password", "canva", "figma", "software", "slack", "zoom"),
        Category.NEWS to setOf("news", "newsdaily", "times", "aftenposten", "dagbladet", "vg", "spiegel", "zeit", "bild", "medium", "substack", "magazine", "avis", "zeitung"),
        Category.GAMING to setOf("xbox", "playstation", "psn", "nintendo", "steam", "twitch", "gamepass", "gaming", "game"),
        Category.EDUCATION to setOf("duolingo", "coursera", "udemy", "babbel", "skillshare", "masterclass", "school", "course", "kurs", "education"),
        Category.UTILITIES to setOf("electricity", "strom", "energi", "energy", "fjordkraft", "tibber", "water", "vann", "rent", "housing", "mortgage", "husleie", "miete"),
    )

    fun guessCategory(tokens: List<String>): Category {
        val lower = tokens.map { it.lowercase() }
        for ((cat, words) in CATEGORY_WORDS) if (lower.any { it in words }) return cat
        return Category.OTHER
    }

    /** Typical monthly amount above which a fixed charge is more likely a bill than a subscription (EUR-equivalent ~150). */
    fun largeFixedThreshold(currency: String): Double = when (currency.uppercase()) {
        "EUR", "USD", "CHF" -> 150.0
        "GBP" -> 130.0
        "NOK", "SEK", "DKK" -> 1500.0
        "PLN" -> 650.0
        "CZK" -> 3700.0
        "HUF" -> 60000.0
        "RON" -> 750.0
        "ISK" -> 20000.0
        else -> 150.0
    }
}
