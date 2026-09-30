import re
from pathlib import Path

from pti_exp.config import SQL
from pti_exp.warehouse import named


def test_named_parameters_become_psycopg_parameters_and_casts_stay():
    query = "SELECT a::text, to_char(t, 'HH24:MI') FROM x WHERE t >= :from AND t < :to AND b LIKE 'g%'"
    assert named(query) == ("SELECT a::text, to_char(t, 'HH24:MI') FROM x WHERE t >= %(from)s AND t < %(to)s "
                            "AND b LIKE 'g%%'")


def test_every_rows_file_has_a_checksum_file_with_the_same_columns():
    for rows in (SQL / "rows").glob("*.sql"):
        checksum = SQL / "checksum" / rows.name
        assert checksum.exists(), rows.name
        assert _columns(rows) == _columns(checksum), rows.name


def _columns(path: Path) -> list[str]:
    text = path.read_text()
    region = text[text.index("concat_ws('|', "):]
    region = region[:min(i for i in (region.find(" AS fp"), region.find("E'\\n'")) if i >= 0)]
    return re.findall(r"(\w+)::text", region)
