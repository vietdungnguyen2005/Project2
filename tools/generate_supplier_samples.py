"""Generate synthetic upload artifacts; source documentation stays UTF-8."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
output = root / "backend" / "target" / "supplier-samples"
output.mkdir(parents=True, exist_ok=True)
source = root / "docs" / "fde" / "samples"
(output / "supplier-a-cp932.csv").write_bytes((source / "supplier-a-utf8-source.csv").read_text(encoding="utf-8").encode("cp932"))
(output / "supplier-b-mixed.csv").write_bytes((source / "supplier-b-mixed.csv").read_bytes())
print(f"Synthetic supplier samples: {output}")
