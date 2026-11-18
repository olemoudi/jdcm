package dev.jdcm.core.weather

/**
 * The 179 municipalities of the Comunidad de Madrid with their INE code, which is also AEMET's id
 * for a municipality's forecast (`localidad_<code>.xml`). From INE's «Relación de municipios y
 * códigos», 1 January 2026 (CPRO 28 + CMUN). The names are INE's, articles last ("Rozas de Madrid,
 * Las"); [codeFor] reads them either way round.
 */
object MadridMunicipalities {
    /** INE code to INE name. */
    val NAMES: Map<String, String> = linkedMapOf(
        "28001" to "Acebeda, La",
        "28002" to "Ajalvir",
        "28003" to "Alameda del Valle",
        "28004" to "Álamo, El",
        "28005" to "Alcalá de Henares",
        "28006" to "Alcobendas",
        "28007" to "Alcorcón",
        "28008" to "Aldea del Fresno",
        "28009" to "Algete",
        "28010" to "Alpedrete",
        "28011" to "Ambite",
        "28012" to "Anchuelo",
        "28013" to "Aranjuez",
        "28014" to "Arganda del Rey",
        "28015" to "Arroyomolinos",
        "28016" to "Atazar, El",
        "28017" to "Batres",
        "28018" to "Becerril de la Sierra",
        "28019" to "Belmonte de Tajo",
        "28020" to "Berzosa del Lozoya",
        "28021" to "Berrueco, El",
        "28022" to "Boadilla del Monte",
        "28023" to "Boalo, El",
        "28024" to "Braojos",
        "28025" to "Brea de Tajo",
        "28026" to "Brunete",
        "28027" to "Buitrago del Lozoya",
        "28028" to "Bustarviejo",
        "28029" to "Cabanillas de la Sierra",
        "28030" to "Cabrera, La",
        "28031" to "Cadalso de los Vidrios",
        "28032" to "Camarma de Esteruelas",
        "28033" to "Campo Real",
        "28034" to "Canencia",
        "28035" to "Carabaña",
        "28036" to "Casarrubuelos",
        "28037" to "Cenicientos",
        "28038" to "Cercedilla",
        "28039" to "Cervera de Buitrago",
        "28040" to "Ciempozuelos",
        "28041" to "Cobeña",
        "28042" to "Colmenar del Arroyo",
        "28043" to "Colmenar de Oreja",
        "28044" to "Colmenarejo",
        "28045" to "Colmenar Viejo",
        "28046" to "Collado Mediano",
        "28047" to "Collado Villalba",
        "28048" to "Corpa",
        "28049" to "Coslada",
        "28050" to "Cubas de la Sagra",
        "28051" to "Chapinería",
        "28052" to "Chinchón",
        "28053" to "Daganzo de Arriba",
        "28054" to "Escorial, El",
        "28055" to "Estremera",
        "28056" to "Fresnedillas de la Oliva",
        "28057" to "Fresno de Torote",
        "28058" to "Fuenlabrada",
        "28059" to "Fuente el Saz de Jarama",
        "28060" to "Fuentidueña de Tajo",
        "28061" to "Galapagar",
        "28062" to "Garganta de los Montes",
        "28063" to "Gargantilla del Lozoya y Pinilla de Buitrago",
        "28064" to "Gascones",
        "28065" to "Getafe",
        "28066" to "Griñón",
        "28067" to "Guadalix de la Sierra",
        "28068" to "Guadarrama",
        "28069" to "Hiruela, La",
        "28070" to "Horcajo de la Sierra-Aoslos",
        "28071" to "Horcajuelo de la Sierra",
        "28072" to "Hoyo de Manzanares",
        "28073" to "Humanes de Madrid",
        "28074" to "Leganés",
        "28075" to "Loeches",
        "28076" to "Lozoya",
        "28078" to "Madarcos",
        "28079" to "Madrid",
        "28080" to "Majadahonda",
        "28082" to "Manzanares el Real",
        "28083" to "Meco",
        "28084" to "Mejorada del Campo",
        "28085" to "Miraflores de la Sierra",
        "28086" to "Molar, El",
        "28087" to "Molinos, Los",
        "28088" to "Montejo de la Sierra",
        "28089" to "Moraleja de Enmedio",
        "28090" to "Moralzarzal",
        "28091" to "Morata de Tajuña",
        "28092" to "Móstoles",
        "28093" to "Navacerrada",
        "28094" to "Navalafuente",
        "28095" to "Navalagamella",
        "28096" to "Navalcarnero",
        "28097" to "Navarredonda y San Mamés",
        "28099" to "Navas del Rey",
        "28100" to "Nuevo Baztán",
        "28101" to "Olmeda de las Fuentes",
        "28102" to "Orusco de Tajuña",
        "28104" to "Paracuellos de Jarama",
        "28106" to "Parla",
        "28107" to "Patones",
        "28108" to "Pedrezuela",
        "28109" to "Pelayos de la Presa",
        "28110" to "Perales de Tajuña",
        "28111" to "Pezuela de las Torres",
        "28112" to "Pinilla del Valle",
        "28113" to "Pinto",
        "28114" to "Piñuécar-Gandullas",
        "28115" to "Pozuelo de Alarcón",
        "28116" to "Pozuelo del Rey",
        "28117" to "Prádena del Rincón",
        "28118" to "Puebla de la Sierra",
        "28119" to "Quijorna",
        "28120" to "Rascafría",
        "28121" to "Redueña",
        "28122" to "Ribatejada",
        "28123" to "Rivas-Vaciamadrid",
        "28124" to "Robledillo de la Jara",
        "28125" to "Robledo de Chavela",
        "28126" to "Robregordo",
        "28127" to "Rozas de Madrid, Las",
        "28128" to "Rozas de Puerto Real",
        "28129" to "San Agustín del Guadalix",
        "28130" to "San Fernando de Henares",
        "28131" to "San Lorenzo de El Escorial",
        "28132" to "San Martín de la Vega",
        "28133" to "San Martín de Valdeiglesias",
        "28134" to "San Sebastián de los Reyes",
        "28135" to "Santa María de la Alameda",
        "28136" to "Santorcaz",
        "28137" to "Santos de la Humosa, Los",
        "28138" to "Serna del Monte, La",
        "28140" to "Serranillos del Valle",
        "28141" to "Sevilla la Nueva",
        "28143" to "Somosierra",
        "28144" to "Soto del Real",
        "28145" to "Talamanca de Jarama",
        "28146" to "Tielmes",
        "28147" to "Titulcia",
        "28148" to "Torrejón de Ardoz",
        "28149" to "Torrejón de la Calzada",
        "28150" to "Torrejón de Velasco",
        "28151" to "Torrelaguna",
        "28152" to "Torrelodones",
        "28153" to "Torremocha de Jarama",
        "28154" to "Torres de la Alameda",
        "28155" to "Valdaracete",
        "28156" to "Valdeavero",
        "28157" to "Valdelaguna",
        "28158" to "Valdemanco",
        "28159" to "Valdemaqueda",
        "28160" to "Valdemorillo",
        "28161" to "Valdemoro",
        "28162" to "Valdeolmos-Alalpardo",
        "28163" to "Valdepiélagos",
        "28164" to "Valdetorres de Jarama",
        "28165" to "Valdilecha",
        "28166" to "Valverde de Alcalá",
        "28167" to "Velilla de San Antonio",
        "28168" to "Vellón, El",
        "28169" to "Venturada",
        "28170" to "Villaconejos",
        "28171" to "Villa del Prado",
        "28172" to "Villalbilla",
        "28173" to "Villamanrique de Tajo",
        "28174" to "Villamanta",
        "28175" to "Villamantilla",
        "28176" to "Villanueva de la Cañada",
        "28177" to "Villanueva del Pardillo",
        "28178" to "Villanueva de Perales",
        "28179" to "Villar del Olmo",
        "28180" to "Villarejo de Salvanés",
        "28181" to "Villaviciosa de Odón",
        "28182" to "Villavieja del Lozoya",
        "28183" to "Zarzalejo",
        "28901" to "Lozoyuela-Navas-Sieteiglesias",
        "28902" to "Puentes Viejas",
        "28903" to "Tres Cantos",
    )

    /** Normalised name, in both article orders, to code. */
    private val BY_NAME: Map<String, String> = buildMap {
        for ((code, name) in NAMES) {
            put(normalise(name), code)
            // "Rozas de Madrid, Las" is "Las Rozas de Madrid" on every sign and on the site.
            val comma = name.lastIndexOf(", ")
            if (comma > 0) put(normalise(name.substring(comma + 2) + " " + name.substring(0, comma)), code)
        }
    }

    /** Madrid city's postcodes (28001-28055): its venues sometimes come with no town. */
    private val CAPITAL_POSTCODES = 28001..28055

    const val MADRID = "28079"

    /** The municipality of a place named by the site ("Madrid", "San Sebastián de los Reyes"), or null. */
    fun codeFor(town: String, postcode: String = ""): String? {
        val t = normalise(town)
        if (t.isNotEmpty()) {
            BY_NAME[t]?.let { return it }
            // "Las Rozas" for "Las Rozas de Madrid", "San Lorenzo" for "San Lorenzo de El Escorial":
            // one municipality whose name starts with the town said, and only one.
            BY_NAME.filterKeys { it.startsWith("$t ") }.values.toSet().singleOrNull()?.let { return it }
        }
        val pc = postcode.trim().toIntOrNull()
        return if (pc != null && pc in CAPITAL_POSTCODES) MADRID else null
    }

    fun normalise(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
