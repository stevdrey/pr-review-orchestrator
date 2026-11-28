CREATE TABLE repository_review_policy_time_zones (
    zone_id text COLLATE "C" PRIMARY KEY
);

-- IDs recognized by the Java 27 runtime that reads the immutable policy versions.
-- The application operator has no write access to this catalog.
INSERT INTO repository_review_policy_time_zones (zone_id)
SELECT unnest(ARRAY[
    'Africa/Abidjan', 'Africa/Accra', 'Africa/Addis_Ababa', 'Africa/Algiers',
    'Africa/Asmara', 'Africa/Asmera', 'Africa/Bamako', 'Africa/Bangui',
    'Africa/Banjul', 'Africa/Bissau', 'Africa/Blantyre', 'Africa/Brazzaville',
    'Africa/Bujumbura', 'Africa/Cairo', 'Africa/Casablanca', 'Africa/Ceuta',
    'Africa/Conakry', 'Africa/Dakar', 'Africa/Dar_es_Salaam', 'Africa/Djibouti',
    'Africa/Douala', 'Africa/El_Aaiun', 'Africa/Freetown', 'Africa/Gaborone',
    'Africa/Harare', 'Africa/Johannesburg', 'Africa/Juba', 'Africa/Kampala',
    'Africa/Khartoum', 'Africa/Kigali', 'Africa/Kinshasa', 'Africa/Lagos',
    'Africa/Libreville', 'Africa/Lome', 'Africa/Luanda', 'Africa/Lubumbashi',
    'Africa/Lusaka', 'Africa/Malabo', 'Africa/Maputo', 'Africa/Maseru',
    'Africa/Mbabane', 'Africa/Mogadishu', 'Africa/Monrovia', 'Africa/Nairobi',
    'Africa/Ndjamena', 'Africa/Niamey', 'Africa/Nouakchott', 'Africa/Ouagadougou',
    'Africa/Porto-Novo', 'Africa/Sao_Tome', 'Africa/Timbuktu', 'Africa/Tripoli',
    'Africa/Tunis', 'Africa/Windhoek', 'America/Adak', 'America/Anchorage',
    'America/Anguilla', 'America/Antigua', 'America/Araguaina', 'America/Argentina/Buenos_Aires',
    'America/Argentina/Catamarca', 'America/Argentina/ComodRivadavia', 'America/Argentina/Cordoba', 'America/Argentina/Jujuy',
    'America/Argentina/La_Rioja', 'America/Argentina/Mendoza', 'America/Argentina/Rio_Gallegos', 'America/Argentina/Salta',
    'America/Argentina/San_Juan', 'America/Argentina/San_Luis', 'America/Argentina/Tucuman', 'America/Argentina/Ushuaia',
    'America/Aruba', 'America/Asuncion', 'America/Atikokan', 'America/Atka',
    'America/Bahia', 'America/Bahia_Banderas', 'America/Barbados', 'America/Belem',
    'America/Belize', 'America/Blanc-Sablon', 'America/Boa_Vista', 'America/Bogota',
    'America/Boise', 'America/Buenos_Aires', 'America/Cambridge_Bay', 'America/Campo_Grande',
    'America/Cancun', 'America/Caracas', 'America/Catamarca', 'America/Cayenne',
    'America/Cayman', 'America/Chicago', 'America/Chihuahua', 'America/Ciudad_Juarez',
    'America/Coral_Harbour', 'America/Cordoba', 'America/Costa_Rica', 'America/Coyhaique',
    'America/Creston', 'America/Cuiaba', 'America/Curacao', 'America/Danmarkshavn',
    'America/Dawson', 'America/Dawson_Creek', 'America/Denver', 'America/Detroit',
    'America/Dominica', 'America/Edmonton', 'America/Eirunepe', 'America/El_Salvador',
    'America/Ensenada', 'America/Fort_Nelson', 'America/Fort_Wayne', 'America/Fortaleza',
    'America/Glace_Bay', 'America/Godthab', 'America/Goose_Bay', 'America/Grand_Turk',
    'America/Grenada', 'America/Guadeloupe', 'America/Guatemala', 'America/Guayaquil',
    'America/Guyana', 'America/Halifax', 'America/Havana', 'America/Hermosillo',
    'America/Indiana/Indianapolis', 'America/Indiana/Knox', 'America/Indiana/Marengo', 'America/Indiana/Petersburg',
    'America/Indiana/Tell_City', 'America/Indiana/Vevay', 'America/Indiana/Vincennes', 'America/Indiana/Winamac',
    'America/Indianapolis', 'America/Inuvik', 'America/Iqaluit', 'America/Jamaica',
    'America/Jujuy', 'America/Juneau', 'America/Kentucky/Louisville', 'America/Kentucky/Monticello',
    'America/Knox_IN', 'America/Kralendijk', 'America/La_Paz', 'America/Lima',
    'America/Los_Angeles', 'America/Louisville', 'America/Lower_Princes', 'America/Maceio',
    'America/Managua', 'America/Manaus', 'America/Marigot', 'America/Martinique',
    'America/Matamoros', 'America/Mazatlan', 'America/Mendoza', 'America/Menominee',
    'America/Merida', 'America/Metlakatla', 'America/Mexico_City', 'America/Miquelon',
    'America/Moncton', 'America/Monterrey', 'America/Montevideo', 'America/Montreal',
    'America/Montserrat', 'America/Nassau', 'America/New_York', 'America/Nipigon',
    'America/Nome', 'America/Noronha', 'America/North_Dakota/Beulah', 'America/North_Dakota/Center',
    'America/North_Dakota/New_Salem', 'America/Nuuk', 'America/Ojinaga', 'America/Panama',
    'America/Pangnirtung', 'America/Paramaribo', 'America/Phoenix', 'America/Port-au-Prince',
    'America/Port_of_Spain', 'America/Porto_Acre', 'America/Porto_Velho', 'America/Puerto_Rico',
    'America/Punta_Arenas', 'America/Rainy_River', 'America/Rankin_Inlet', 'America/Recife',
    'America/Regina', 'America/Resolute', 'America/Rio_Branco', 'America/Rosario',
    'America/Santa_Isabel', 'America/Santarem', 'America/Santiago', 'America/Santo_Domingo',
    'America/Sao_Paulo', 'America/Scoresbysund', 'America/Shiprock', 'America/Sitka',
    'America/St_Barthelemy', 'America/St_Johns', 'America/St_Kitts', 'America/St_Lucia',
    'America/St_Thomas', 'America/St_Vincent', 'America/Swift_Current', 'America/Tegucigalpa',
    'America/Thule', 'America/Thunder_Bay', 'America/Tijuana', 'America/Toronto',
    'America/Tortola', 'America/Vancouver', 'America/Virgin', 'America/Whitehorse',
    'America/Winnipeg', 'America/Yakutat', 'America/Yellowknife', 'Antarctica/Casey',
    'Antarctica/Davis', 'Antarctica/DumontDUrville', 'Antarctica/Macquarie', 'Antarctica/Mawson',
    'Antarctica/McMurdo', 'Antarctica/Palmer', 'Antarctica/Rothera', 'Antarctica/South_Pole',
    'Antarctica/Syowa', 'Antarctica/Troll', 'Antarctica/Vostok', 'Arctic/Longyearbyen',
    'Asia/Aden', 'Asia/Almaty', 'Asia/Amman', 'Asia/Anadyr',
    'Asia/Aqtau', 'Asia/Aqtobe', 'Asia/Ashgabat', 'Asia/Ashkhabad',
    'Asia/Atyrau', 'Asia/Baghdad', 'Asia/Bahrain', 'Asia/Baku',
    'Asia/Bangkok', 'Asia/Barnaul', 'Asia/Beirut', 'Asia/Bishkek',
    'Asia/Brunei', 'Asia/Calcutta', 'Asia/Chita', 'Asia/Choibalsan',
    'Asia/Chongqing', 'Asia/Chungking', 'Asia/Colombo', 'Asia/Dacca',
    'Asia/Damascus', 'Asia/Dhaka', 'Asia/Dili', 'Asia/Dubai',
    'Asia/Dushanbe', 'Asia/Famagusta', 'Asia/Gaza', 'Asia/Harbin',
    'Asia/Hebron', 'Asia/Ho_Chi_Minh', 'Asia/Hong_Kong', 'Asia/Hovd',
    'Asia/Irkutsk', 'Asia/Istanbul', 'Asia/Jakarta', 'Asia/Jayapura',
    'Asia/Jerusalem', 'Asia/Kabul', 'Asia/Kamchatka', 'Asia/Karachi',
    'Asia/Kashgar', 'Asia/Kathmandu', 'Asia/Katmandu', 'Asia/Khandyga',
    'Asia/Kolkata', 'Asia/Krasnoyarsk', 'Asia/Kuala_Lumpur', 'Asia/Kuching',
    'Asia/Kuwait', 'Asia/Macao', 'Asia/Macau', 'Asia/Magadan',
    'Asia/Makassar', 'Asia/Manila', 'Asia/Muscat', 'Asia/Nicosia',
    'Asia/Novokuznetsk', 'Asia/Novosibirsk', 'Asia/Omsk', 'Asia/Oral',
    'Asia/Phnom_Penh', 'Asia/Pontianak', 'Asia/Pyongyang', 'Asia/Qatar',
    'Asia/Qostanay', 'Asia/Qyzylorda', 'Asia/Rangoon', 'Asia/Riyadh',
    'Asia/Saigon', 'Asia/Sakhalin', 'Asia/Samarkand', 'Asia/Seoul',
    'Asia/Shanghai', 'Asia/Singapore', 'Asia/Srednekolymsk', 'Asia/Taipei',
    'Asia/Tashkent', 'Asia/Tbilisi', 'Asia/Tehran', 'Asia/Tel_Aviv',
    'Asia/Thimbu', 'Asia/Thimphu', 'Asia/Tokyo', 'Asia/Tomsk',
    'Asia/Ujung_Pandang', 'Asia/Ulaanbaatar', 'Asia/Ulan_Bator', 'Asia/Urumqi',
    'Asia/Ust-Nera', 'Asia/Vientiane', 'Asia/Vladivostok', 'Asia/Yakutsk',
    'Asia/Yangon', 'Asia/Yekaterinburg', 'Asia/Yerevan', 'Atlantic/Azores',
    'Atlantic/Bermuda', 'Atlantic/Canary', 'Atlantic/Cape_Verde', 'Atlantic/Faeroe',
    'Atlantic/Faroe', 'Atlantic/Jan_Mayen', 'Atlantic/Madeira', 'Atlantic/Reykjavik',
    'Atlantic/South_Georgia', 'Atlantic/St_Helena', 'Atlantic/Stanley', 'Australia/ACT',
    'Australia/Adelaide', 'Australia/Brisbane', 'Australia/Broken_Hill', 'Australia/Canberra',
    'Australia/Currie', 'Australia/Darwin', 'Australia/Eucla', 'Australia/Hobart',
    'Australia/LHI', 'Australia/Lindeman', 'Australia/Lord_Howe', 'Australia/Melbourne',
    'Australia/NSW', 'Australia/North', 'Australia/Perth', 'Australia/Queensland',
    'Australia/South', 'Australia/Sydney', 'Australia/Tasmania', 'Australia/Victoria',
    'Australia/West', 'Australia/Yancowinna', 'Brazil/Acre', 'Brazil/DeNoronha',
    'Brazil/East', 'Brazil/West', 'CET', 'CST6CDT',
    'Canada/Atlantic', 'Canada/Central', 'Canada/Eastern', 'Canada/Mountain',
    'Canada/Newfoundland', 'Canada/Pacific', 'Canada/Saskatchewan', 'Canada/Yukon',
    'Chile/Continental', 'Chile/EasterIsland', 'Cuba', 'EET',
    'EST5EDT', 'Egypt', 'Eire', 'Etc/GMT',
    'Etc/GMT+0', 'Etc/GMT+1', 'Etc/GMT+10', 'Etc/GMT+11',
    'Etc/GMT+12', 'Etc/GMT+2', 'Etc/GMT+3', 'Etc/GMT+4',
    'Etc/GMT+5', 'Etc/GMT+6', 'Etc/GMT+7', 'Etc/GMT+8',
    'Etc/GMT+9', 'Etc/GMT-0', 'Etc/GMT-1', 'Etc/GMT-10',
    'Etc/GMT-11', 'Etc/GMT-12', 'Etc/GMT-13', 'Etc/GMT-14',
    'Etc/GMT-2', 'Etc/GMT-3', 'Etc/GMT-4', 'Etc/GMT-5',
    'Etc/GMT-6', 'Etc/GMT-7', 'Etc/GMT-8', 'Etc/GMT-9',
    'Etc/GMT0', 'Etc/Greenwich', 'Etc/UCT', 'Etc/UTC',
    'Etc/Universal', 'Etc/Zulu', 'Europe/Amsterdam', 'Europe/Andorra',
    'Europe/Astrakhan', 'Europe/Athens', 'Europe/Belfast', 'Europe/Belgrade',
    'Europe/Berlin', 'Europe/Bratislava', 'Europe/Brussels', 'Europe/Bucharest',
    'Europe/Budapest', 'Europe/Busingen', 'Europe/Chisinau', 'Europe/Copenhagen',
    'Europe/Dublin', 'Europe/Gibraltar', 'Europe/Guernsey', 'Europe/Helsinki',
    'Europe/Isle_of_Man', 'Europe/Istanbul', 'Europe/Jersey', 'Europe/Kaliningrad',
    'Europe/Kiev', 'Europe/Kirov', 'Europe/Kyiv', 'Europe/Lisbon',
    'Europe/Ljubljana', 'Europe/London', 'Europe/Luxembourg', 'Europe/Madrid',
    'Europe/Malta', 'Europe/Mariehamn', 'Europe/Minsk', 'Europe/Monaco',
    'Europe/Moscow', 'Europe/Nicosia', 'Europe/Oslo', 'Europe/Paris',
    'Europe/Podgorica', 'Europe/Prague', 'Europe/Riga', 'Europe/Rome',
    'Europe/Samara', 'Europe/San_Marino', 'Europe/Sarajevo', 'Europe/Saratov',
    'Europe/Simferopol', 'Europe/Skopje', 'Europe/Sofia', 'Europe/Stockholm',
    'Europe/Tallinn', 'Europe/Tirane', 'Europe/Tiraspol', 'Europe/Ulyanovsk',
    'Europe/Uzhgorod', 'Europe/Vaduz', 'Europe/Vatican', 'Europe/Vienna',
    'Europe/Vilnius', 'Europe/Volgograd', 'Europe/Warsaw', 'Europe/Zagreb',
    'Europe/Zaporozhye', 'Europe/Zurich', 'GB', 'GB-Eire',
    'GMT', 'GMT0', 'Greenwich', 'Hongkong',
    'Iceland', 'Indian/Antananarivo', 'Indian/Chagos', 'Indian/Christmas',
    'Indian/Cocos', 'Indian/Comoro', 'Indian/Kerguelen', 'Indian/Mahe',
    'Indian/Maldives', 'Indian/Mauritius', 'Indian/Mayotte', 'Indian/Reunion',
    'Iran', 'Israel', 'Jamaica', 'Japan',
    'Kwajalein', 'Libya', 'MET', 'MST7MDT',
    'Mexico/BajaNorte', 'Mexico/BajaSur', 'Mexico/General', 'NZ',
    'NZ-CHAT', 'Navajo', 'PRC', 'PST8PDT',
    'Pacific/Apia', 'Pacific/Auckland', 'Pacific/Bougainville', 'Pacific/Chatham',
    'Pacific/Chuuk', 'Pacific/Easter', 'Pacific/Efate', 'Pacific/Enderbury',
    'Pacific/Fakaofo', 'Pacific/Fiji', 'Pacific/Funafuti', 'Pacific/Galapagos',
    'Pacific/Gambier', 'Pacific/Guadalcanal', 'Pacific/Guam', 'Pacific/Honolulu',
    'Pacific/Johnston', 'Pacific/Kanton', 'Pacific/Kiritimati', 'Pacific/Kosrae',
    'Pacific/Kwajalein', 'Pacific/Majuro', 'Pacific/Marquesas', 'Pacific/Midway',
    'Pacific/Nauru', 'Pacific/Niue', 'Pacific/Norfolk', 'Pacific/Noumea',
    'Pacific/Pago_Pago', 'Pacific/Palau', 'Pacific/Pitcairn', 'Pacific/Pohnpei',
    'Pacific/Ponape', 'Pacific/Port_Moresby', 'Pacific/Rarotonga', 'Pacific/Saipan',
    'Pacific/Samoa', 'Pacific/Tahiti', 'Pacific/Tarawa', 'Pacific/Tongatapu',
    'Pacific/Truk', 'Pacific/Wake', 'Pacific/Wallis', 'Pacific/Yap',
    'Poland', 'Portugal', 'ROK', 'Singapore',
    'SystemV/AST4', 'SystemV/AST4ADT', 'SystemV/CST6', 'SystemV/CST6CDT',
    'SystemV/EST5', 'SystemV/EST5EDT', 'SystemV/HST10', 'SystemV/MST7',
    'SystemV/MST7MDT', 'SystemV/PST8', 'SystemV/PST8PDT', 'SystemV/YST9',
    'SystemV/YST9YDT', 'Turkey', 'UCT', 'US/Alaska',
    'US/Aleutian', 'US/Arizona', 'US/Central', 'US/East-Indiana',
    'US/Eastern', 'US/Hawaii', 'US/Indiana-Starke', 'US/Michigan',
    'US/Mountain', 'US/Pacific', 'US/Samoa', 'UTC',
    'Universal', 'W-SU', 'WET', 'Zulu'
]);

CREATE TABLE repository_review_policy_versions (
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    stages text[] NOT NULL CHECK (stages <@ ARRAY['BASELINE', 'DEEP']::text[] AND array_position(stages, NULL) IS NULL),
    providers text[] NOT NULL CHECK (cardinality(providers) <= 16),
    require_passing_ci boolean NOT NULL,
    quiet_zone text COLLATE "C" REFERENCES repository_review_policy_time_zones (zone_id),
    quiet_start_minute integer CHECK (quiet_start_minute BETWEEN 0 AND 1439),
    quiet_end_minute integer CHECK (quiet_end_minute BETWEEN 0 AND 1439),
    iteration_cap integer NOT NULL CHECK (iteration_cap BETWEEN 0 AND 6),
    held boolean NOT NULL,
    changed_by bigint NOT NULL CHECK (changed_by > 0),
    change_kind text NOT NULL CHECK (change_kind IN ('CONFIGURE', 'PAUSE', 'RESUME')),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (installation_id, repository_id, version),
    FOREIGN KEY (installation_id, repository_id) REFERENCES installation_repositories (installation_id, repository_id),
    CHECK (num_nonnulls(quiet_zone, quiet_start_minute, quiet_end_minute) IN (0, 3)),
    CHECK (quiet_start_minute <> quiet_end_minute)
);

CREATE FUNCTION reject_repository_review_policy_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Repository review policy versions are append-only';
END;
$$;
CREATE TRIGGER repository_review_policy_append_only BEFORE UPDATE OR DELETE ON repository_review_policy_versions
    FOR EACH ROW EXECUTE FUNCTION reject_repository_review_policy_mutation();

-- Provisioned by a trusted database administrator. The CLI authenticates as this named login.
CREATE TABLE repository_review_policy_operators (
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    database_role name NOT NULL,
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    PRIMARY KEY (installation_id, repository_id, database_role),
    FOREIGN KEY (installation_id, repository_id) REFERENCES installation_repositories (installation_id, repository_id)
);

-- Both policy entry points and the insert trigger require the named, scoped login and live membership.
CREATE FUNCTION require_repository_review_policy_operator(
    p_installation bigint, p_repository bigint, p_actor bigint
) RETURNS void LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1
    FROM "${flyway:defaultSchema}".repository_review_policy_operators o
    JOIN "${flyway:defaultSchema}".installation_repositories r USING (installation_id, repository_id)
    JOIN "${flyway:defaultSchema}".installations i USING (installation_id)
    WHERE (o.installation_id, o.repository_id, o.actor_id, o.database_role) =
        (p_installation, p_repository, p_actor, session_user)
        AND i.status = 'ACTIVE' AND r.enabled
    FOR SHARE OF i, r, o;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Repository policy operator denied' USING ERRCODE = '42501';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION require_repository_review_policy_operator(bigint, bigint, bigint) FROM PUBLIC;

CREATE FUNCTION authorize_repository_review_policy_insert() RETURNS trigger LANGUAGE plpgsql
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    previous record;
BEGIN
    PERFORM "${flyway:defaultSchema}".require_repository_review_policy_operator(
        NEW.installation_id, NEW.repository_id, NEW.changed_by);
    IF EXISTS (
        SELECT 1 FROM unnest(NEW.providers) AS items(provider)
        WHERE provider IS NULL OR provider COLLATE "C" !~ '^[a-z0-9_-]{1,32}$'
    ) THEN
        RAISE EXCEPTION 'Invalid repository review provider' USING ERRCODE = '23514';
    END IF;

    -- Persist the same ordering and uniqueness as the policy sets used by the application.
    IF NEW.stages IS NOT NULL THEN
        NEW.stages := ARRAY(SELECT DISTINCT stage COLLATE "C" FROM unnest(NEW.stages) AS items(stage) ORDER BY 1);
    END IF;
    IF NEW.providers IS NOT NULL THEN
        NEW.providers := ARRAY(SELECT DISTINCT provider COLLATE "C" FROM unnest(NEW.providers) AS items(provider) ORDER BY 1);
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        'review-policy:' || NEW.installation_id || ':' || NEW.repository_id, 0));
    SELECT version, stages, providers, require_passing_ci, quiet_zone, quiet_start_minute,
            quiet_end_minute, iteration_cap, held
    INTO previous FROM "${flyway:defaultSchema}".repository_review_policy_versions
    WHERE (installation_id, repository_id) = (NEW.installation_id, NEW.repository_id)
    ORDER BY version DESC LIMIT 1;

    IF NEW.version <> coalesce(previous.version, 0) + 1
        OR (NEW.change_kind = 'CONFIGURE' AND NEW.held <> coalesce(previous.held, false))
        OR (NEW.change_kind <> 'CONFIGURE' AND (
            NEW.held <> (NEW.change_kind = 'PAUSE')
            OR (NEW.stages, NEW.providers, NEW.require_passing_ci, NEW.quiet_zone, NEW.quiet_start_minute,
                    NEW.quiet_end_minute, NEW.iteration_cap)
                IS DISTINCT FROM (coalesce(previous.stages, ARRAY[]::text[]), coalesce(previous.providers, ARRAY[]::text[]),
                    coalesce(previous.require_passing_ci, true), previous.quiet_zone, previous.quiet_start_minute,
                    previous.quiet_end_minute, coalesce(previous.iteration_cap, 0)))) THEN
        RAISE EXCEPTION 'Invalid repository policy version or operation' USING ERRCODE = '23514';
    END IF;
    NEW.changed_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER repository_review_policy_authorized_insert BEFORE INSERT ON repository_review_policy_versions
    FOR EACH ROW EXECUTE FUNCTION authorize_repository_review_policy_insert();
REVOKE ALL ON FUNCTION authorize_repository_review_policy_insert() FROM PUBLIC;

-- The CLI reads via this scoped entry point before comparing the expected version.
CREATE FUNCTION read_repository_review_policy(
    p_installation bigint, p_repository bigint, p_actor bigint, p_for_update boolean
) RETURNS SETOF "${flyway:defaultSchema}".repository_review_policy_versions
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    policy_lock bigint := hashtextextended('review-policy:' || p_installation || ':' || p_repository, 0);
BEGIN
    PERFORM "${flyway:defaultSchema}".require_repository_review_policy_operator(p_installation, p_repository, p_actor);
    IF p_for_update THEN
        PERFORM pg_advisory_xact_lock(policy_lock);
    ELSE
        PERFORM pg_advisory_xact_lock_shared(policy_lock);
    END IF;
    RETURN QUERY SELECT v.* FROM "${flyway:defaultSchema}".repository_review_policy_versions v
    WHERE (v.installation_id, v.repository_id) = (p_installation, p_repository)
    ORDER BY v.version DESC LIMIT 1;
END;
$$;
REVOKE ALL ON FUNCTION read_repository_review_policy(bigint, bigint, bigint, boolean) FROM PUBLIC;

-- All inserts go through the same trigger, which authenticates the session and validates the version.
CREATE FUNCTION append_repository_review_policy(
    p_installation bigint, p_repository bigint, p_version bigint, p_stages text[], p_providers text[],
    p_require_ci boolean, p_zone text, p_start integer, p_end integer, p_cap integer, p_held boolean,
    p_actor bigint, p_change text
) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    INSERT INTO "${flyway:defaultSchema}".repository_review_policy_versions (
        installation_id, repository_id, version, stages, providers, require_passing_ci,
        quiet_zone, quiet_start_minute, quiet_end_minute, iteration_cap, held, changed_by, change_kind
    ) VALUES (p_installation, p_repository, p_version, p_stages, p_providers, p_require_ci,
        p_zone, p_start, p_end, p_cap, p_held, p_actor, p_change);
$$;
REVOKE ALL ON FUNCTION append_repository_review_policy(
    bigint, bigint, bigint, text[], text[], boolean, text, integer, integer, integer, boolean, bigint, text
) FROM PUBLIC;

-- Historical work has no reliable policy provenance. Keep receipts and keys without synthesizing one.
ALTER TABLE outbox_commands ADD COLUMN repository_policy_version bigint,
    ADD FOREIGN KEY (installation_id, repository_id, repository_policy_version)
        REFERENCES repository_review_policy_versions (installation_id, repository_id, version),
    ADD CHECK (repository_policy_version IS NULL OR command_type = 'REQUEST_REVIEW');
ALTER TABLE review_requests ADD COLUMN repository_policy_version bigint,
    ADD FOREIGN KEY (installation_id, repository_id, repository_policy_version)
        REFERENCES repository_review_policy_versions (installation_id, repository_id, version);

CREATE FUNCTION reject_review_policy_repin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.repository_policy_version IS NOT NULL OR OLD.repository_policy_version IS NOT NULL)
        AND (NEW.installation_id, NEW.repository_id, NEW.repository_policy_version)
            IS DISTINCT FROM (OLD.installation_id, OLD.repository_id, OLD.repository_policy_version) THEN
        RAISE EXCEPTION 'Review policy reference is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbox_review_policy_pin BEFORE UPDATE OF installation_id, repository_id, repository_policy_version ON outbox_commands
    FOR EACH ROW EXECUTE FUNCTION reject_review_policy_repin();
CREATE TRIGGER request_review_policy_pin BEFORE UPDATE OF installation_id, repository_id, repository_policy_version ON review_requests
    FOR EACH ROW EXECUTE FUNCTION reject_review_policy_repin();

ALTER TABLE queue_transitions DROP CONSTRAINT queue_transitions_error_code_check;
ALTER TABLE queue_transitions ADD CONSTRAINT queue_transitions_error_code_check
    CHECK (error_code IN ('INTERNAL_ERROR', 'AUTHORIZATION_DENIED', 'AUTHORIZATION_UNAVAILABLE',
        'INVALID_EVENT', 'UNSUPPORTED_COMMAND', 'REMOTE_UNAVAILABLE', 'REMOTE_UNCERTAIN', 'LEASE_EXHAUSTED',
        'POLICY_DENIED'));
