"""tell identical debts apart

A line that asked for five spots in a week and got one is owed four, and the
gather reports four debts that are identical in every column. Keyed on line,
day and cause alone they collapsed into a single row, and the client was
quietly owed a quarter of what they were actually owed.

The ordinal is assigned by counting identical debts within one gather, so the
same span gathered again numbers them the same way and the nightly job stays a
no-op on a second run.

Revision ID: 0007
Revises: 0006
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("makegood_debt") as batch:
        batch.add_column(sa.Column("sequence", sa.Integer(), nullable=False, server_default="1"))
        batch.drop_constraint("uq_makegood_debt_line_day_cause", type_="unique")
        batch.create_unique_constraint(
            "uq_makegood_debt_line_day_cause_sequence",
            ["order_number", "line", "day", "cause", "sequence"],
        )


def downgrade() -> None:
    with op.batch_alter_table("makegood_debt") as batch:
        batch.drop_constraint("uq_makegood_debt_line_day_cause_sequence", type_="unique")
        batch.create_unique_constraint(
            "uq_makegood_debt_line_day_cause", ["order_number", "line", "day", "cause"]
        )
        batch.drop_column("sequence")
