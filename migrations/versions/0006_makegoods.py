"""the makegood ledger

Two tables rather than one. A debt is raised the night the as-run says a spot
did not run and stays raised until something makes it good. An offer is a
conversation that can be refused, expire, and be followed by another offer
against the same debt.

The unique key on a debt carries the cause. The same spot really can be owed
twice on one day, once because it errored and once because a political order
bumped a different airing, and those are two spots to give back.

Revision ID: 0006
Revises: 0005
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "makegood_debt",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column(
            "order_number",
            sa.String(length=24),
            sa.ForeignKey("sales_order.number"),
            nullable=False,
        ),
        sa.Column("line", sa.Integer(), nullable=False),
        sa.Column("advertiser", sa.String(length=16), nullable=False),
        sa.Column("station", sa.String(length=16), nullable=False),
        sa.Column("daypart", sa.String(length=16), nullable=False),
        sa.Column("grade", sa.String(length=32), nullable=False),
        sa.Column("length", sa.Integer(), nullable=False),
        sa.Column("rate", sa.Integer(), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("cause", sa.String(length=32), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_makegood_debt"),
        sa.UniqueConstraint(
            "order_number", "line", "day", "cause", name="uq_makegood_debt_line_day_cause"
        ),
    )
    op.create_index("ix_makegood_debt_order_number", "makegood_debt", ["order_number"])
    op.create_index("ix_makegood_debt_station", "makegood_debt", ["station"])
    op.create_index("ix_makegood_debt_day", "makegood_debt", ["day"])

    op.create_table(
        "makegood_offer",
        sa.Column("id", sa.Integer(), autoincrement=True, nullable=False),
        sa.Column("debt_id", sa.Integer(), sa.ForeignKey("makegood_debt.id"), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("daypart", sa.String(length=16), nullable=False),
        sa.Column("rate", sa.Integer(), nullable=False),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column("short", sa.Boolean(), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_makegood_offer"),
    )
    op.create_index("ix_makegood_offer_debt_id", "makegood_offer", ["debt_id"])
    op.create_index("ix_makegood_offer_day", "makegood_offer", ["day"])


def downgrade() -> None:
    op.drop_index("ix_makegood_offer_day", table_name="makegood_offer")
    op.drop_index("ix_makegood_offer_debt_id", table_name="makegood_offer")
    op.drop_table("makegood_offer")
    op.drop_index("ix_makegood_debt_day", table_name="makegood_debt")
    op.drop_index("ix_makegood_debt_station", table_name="makegood_debt")
    op.drop_index("ix_makegood_debt_order_number", table_name="makegood_debt")
    op.drop_table("makegood_debt")
