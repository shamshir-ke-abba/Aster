#!/usr/bin/env bash
# ==============================================================================
# Aster — Production SQLite Mailbox Online Backup Script
#
# Safe, online backup using SQLite's online backup API (.backup / VACUUM INTO)
# compatible with active Write-Ahead Logging (WAL) concurrency.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SOURCE_DB="${ASTER_DB_PATH:-${REPO_ROOT}/backend/aster.db}"
BACKUP_DIR="${ASTER_BACKUP_DIR:-${REPO_ROOT}/backups}"

TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
BACKUP_FILE="${BACKUP_DIR}/aster_backup_${TIMESTAMP}.db"

echo "=== Aster SQLite Mailbox Online Backup ==="
echo "Source Database: ${SOURCE_DB}"
echo "Backup Destination: ${BACKUP_FILE}"

if [ ! -f "${SOURCE_DB}" ]; then
    echo "ERROR: Source database not found at ${SOURCE_DB}" >&2
    exit 1
fi

mkdir -p "${BACKUP_DIR}"

if ! command -v sqlite3 &> /dev/null; then
    echo "ERROR: sqlite3 CLI utility not found in PATH" >&2
    exit 1
fi

echo "--> Executing online WAL-consistent backup via sqlite3 .backup API..."
sqlite3 "${SOURCE_DB}" ".backup '${BACKUP_FILE}'"

if [ ! -f "${BACKUP_FILE}" ] || [ ! -s "${BACKUP_FILE}" ]; then
    echo "ERROR: Backup file was not created or is empty!" >&2
    exit 1
fi

BACKUP_SIZE="$(du -h "${BACKUP_FILE}" | cut -f1)"
echo "--> Backup created successfully (Size: ${BACKUP_SIZE})"

echo "--> Verifying backup database integrity..."
INTEGRITY_CHECK="$(sqlite3 "${BACKUP_FILE}" "PRAGMA integrity_check;")"

if [ "${INTEGRITY_CHECK}" = "ok" ]; then
    echo "--> Integrity Check: PASSED (PRAGMA integrity_check = ok)"
else
    echo "ERROR: Integrity check failed with output: ${INTEGRITY_CHECK}" >&2
    exit 2
fi

# Rotate old backups keeping last 14 copies
echo "--> Cleaning up backups older than 14 days..."
find "${BACKUP_DIR}" -name "aster_backup_*.db" -type f -mtime +14 -delete 2>/dev/null || true

echo "=== Backup Complete: ${BACKUP_FILE} ==="
