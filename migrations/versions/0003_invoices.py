"""the invoice, its detail and its summary

Three tables and no clever normalisation. An invoice is a statement of what we
said we were owed on a date, so the money is stored rather than derived and the
detail is copied rather than joined. Both of those look like duplication and
both of them are the point: everything an invoice was built from can be revised
afterwards and the invoice itself cannot.

Revision ID: 0003
Revises: 0002
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "invoice",
        sa.Column("number", sa.String(length=24), nullable=False),
        sa.Column(
            "order_number",
            sa.String(length=24),
            sa.ForeignKey("sales_order.number"),
            nullable=False,
        ),
        sa.Column(
            "advertiser", sa.String(length=16), sa.ForeignKey("advertiser.code"), nullable=False
        ),
        sa.Column("payer", sa.String(length=16), nullable=False),
        sa.Column("year", sa.Integer(), nullable=False),
        sa.Column("month", sa.Integer(), nullable=False),
        sa.Column("raised", sa.Date(), nullable=False),
        sa.Column("spots", sa.Integer(), nullable=False),
        sa.Column("gross", sa.Integer(), nullable=False),
        sa.Column("commission_rate", sa.Numeric(precision=5, scale=4), nullable=False),
        sa.Column("commission", sa.Integer(), nullable=False),
        sa.Column("net", sa.Integer(), nullable=False),
        sa.Column("sent", sa.Boolean(), nullable=False),
        sa.PrimaryKeyConstraint("number", name="pk_invoice"),
        sa.UniqueConstraint(
            "order_number", "year", "month", name="uq_invoice_order_number_year_month"
        ),
    )
    op.create_index("ix_invoice_raised", "invoice", ["raised"], unique=False)
    op.create_table(
        "invoice_detail",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column(
            "invoice_number",
            sa.String(length=24),
            sa.ForeignKey("invoice.number", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("at", sa.Integer(), nullable=False),
        sa.Column("line", sa.Integer(), nullable=False),
        sa.Column("length", sa.Integer(), nullable=False),
        sa.Column("rate", sa.Integer(), nullable=False),
        sa.Column("cart", sa.String(length=16), nullable=False),
        sa.Column("moved", sa.Boolean(), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_invoice_detail"),
    )
    op.create_table(
        "invoice_summary",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column(
            "invoice_number",
            sa.String(length=24),
            sa.ForeignKey("invoice.number", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("line", sa.Integer(), nullable=False),
        sa.Column("station", sa.String(length=16), nullable=False),
        sa.Column("daypart", sa.String(length=16), nullable=False),
        sa.Column("length", sa.Integer(), nullable=False),
        sa.Column("rate", sa.Integer(), nullable=False),
        sa.Column("spots", sa.Integer(), nullable=False),
        sa.Column("gross", sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_invoice_summary"),
        sa.UniqueConstraint(
            "invoice_number", "line", name="uq_invoice_summary_invoice_number_line"
        ),
    )


def downgrade() -> None:
    op.drop_table("invoice_summary")
    op.drop_table("invoice_detail")
    op.drop_index("ix_invoice_raised", table_name="invoice")
    op.drop_table("invoice")
