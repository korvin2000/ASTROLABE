# inventory

Stock levels and reorder decisions for the warehouse.

**Reorder rule.** An item is reordered when its on-hand stock is at or below its reorder point. The order brings the
item back to its target level (`target - on_hand`). Both the single-item check (`needs_reorder`, `order_for`) and the
nightly recount (`reorder_batch`, which feeds `reorder_report`) follow this rule.

Tests: `python -m unittest discover -s tests`.
