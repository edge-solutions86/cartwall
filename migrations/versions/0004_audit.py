"""the audit trail

One table, append only, with no foreign keys out of it. An entry has to outlive
the document it describes: an order that is deleted, a log that is revised away,
an advertiser closed and purged. A trail that cascades away with the thing it
recorded is not a trail.

Two indexes and no more. One on the document, because that is the question
everybody asks, and one on the timestamp, because that is the other one.

Revision ID: 0004
Revises: 0003
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "audit",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("at", sa.DateTime(), nullable=False),
        sa.Column("action", sa.String(length=120), nullable=False),
        sa.Column("actor", sa.String(length=120), nullable=False),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("reference", sa.String(length=64), nullable=False),
        sa.Column("detail", sa.Text(), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_audit"),
    )
    op.create_index("ix_audit_subject", "audit", ["kind", "reference"], unique=False)
    op.create_index("ix_audit_at", "audit", ["at"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_audit_at", table_name="audit")
    op.drop_index("ix_audit_subject", table_name="audit")
    op.drop_table("audit")
