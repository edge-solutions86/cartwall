"""credit memos

An invoice that has gone out does not change. When it turns out to be wrong the
answer is a second document that says so, and both stay on file.

The reason is a short fixed list rather than free text because the month end
report groups by it, and three spellings of customer complaint are three rows
nobody wanted.

Revision ID: 0005
Revises: 0004
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "credit_memo",
        sa.Column("number", sa.String(length=24), nullable=False),
        sa.Column(
            "invoice_number", sa.String(length=24), sa.ForeignKey("invoice.number"), nullable=False
        ),
        sa.Column(
            "advertiser", sa.String(length=16), sa.ForeignKey("advertiser.code"), nullable=False
        ),
        sa.Column("payer", sa.String(length=16), nullable=False),
        sa.Column("raised", sa.Date(), nullable=False),
        sa.Column("reason", sa.String(length=32), nullable=False),
        sa.Column("note", sa.String(length=120), nullable=False),
        sa.Column("gross", sa.Integer(), nullable=False),
        sa.Column("commission", sa.Integer(), nullable=False),
        sa.Column("net", sa.Integer(), nullable=False),
        sa.Column("sent", sa.Boolean(), nullable=False),
        sa.Column("spots", sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint("number", name="pk_credit_memo"),
    )
    op.create_index("ix_credit_memo_raised", "credit_memo", ["raised"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_credit_memo_raised", table_name="credit_memo")
    op.drop_table("credit_memo")
