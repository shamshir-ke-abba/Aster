import { DurableObject } from "cloudflare:workers";
import {
  Env,
  GroupMemberSummary,
  GroupSummary,
  ProtocolLimits,
} from "../protocol/types";

export class GroupDO extends DurableObject<Env> {
  private firstRow(query: string, ...params: any[]): any {
    const rows = this.ctx.storage.sql.exec(query, ...params).toArray();
    return rows.length > 0 ? rows[0] : null;
  }
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.initDatabase();
  }

  private initDatabase(): void {
    this.ctx.storage.sql.exec(`
      PRAGMA foreign_keys = ON;

      CREATE TABLE IF NOT EXISTS groups (
          group_id TEXT PRIMARY KEY NOT NULL,
          name TEXT NOT NULL,
          creator_user_id TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          is_active INTEGER NOT NULL DEFAULT 1
      );

      CREATE TABLE IF NOT EXISTS group_members (
          group_id TEXT NOT NULL,
          user_id TEXT NOT NULL,
          role TEXT NOT NULL,
          joined_at INTEGER NOT NULL,
          is_active INTEGER NOT NULL DEFAULT 1,
          left_at INTEGER,
          PRIMARY KEY (group_id, user_id),
          FOREIGN KEY (group_id) REFERENCES groups(group_id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS idx_group_members_user ON group_members (user_id, is_active);
      CREATE INDEX IF NOT EXISTS idx_group_members_group ON group_members (group_id, is_active);
    `);
  }

  async create(groupId: string, name: string, creatorId: string, memberIds: string[]): Promise<GroupSummary> {
    const now = Date.now();
    this.ctx.storage.sql.exec(
      `INSERT INTO groups (group_id, name, creator_user_id, created_at, updated_at, is_active)
       VALUES (?, ?, ?, ?, ?, 1)`,
      groupId,
      name,
      creatorId,
      now,
      now
    );

    // Creator is always added as CREATOR
    this.ctx.storage.sql.exec(
      `INSERT INTO group_members (group_id, user_id, role, joined_at, is_active)
       VALUES (?, ?, 'CREATOR', ?, 1)`,
      groupId,
      creatorId,
      now
    );

    // Add initial members
    for (const memberId of memberIds) {
      if (memberId !== creatorId && ProtocolLimits.USER_ID_REGEX.test(memberId)) {
        this.ctx.storage.sql.exec(
          `INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at, is_active)
           VALUES (?, ?, 'MEMBER', ?, 1)`,
          groupId,
          memberId,
          now
        );
      }
    }

    const members = await this.getActiveMemberIds();
    return {
      id: groupId,
      name,
      creatorId,
      memberCount: members.length,
      role: "CREATOR",
      createdAt: now,
      updatedAt: now,
    };
  }

  async getSummary(requesterUserId: string): Promise<GroupSummary | null> {
    const group = this.firstRow(
      `SELECT group_id, name, creator_user_id, created_at, updated_at, is_active
       FROM groups WHERE is_active = 1`
    );

    if (!group) return null;

    const member = this.firstRow(
      `SELECT role, is_active FROM group_members WHERE user_id = ? AND is_active = 1`,
      requesterUserId
    );

    if (!member) return null;

    const countRow = this.firstRow(
      `SELECT COUNT(*) as count FROM group_members WHERE is_active = 1`
    );

    return {
      id: group.group_id,
      name: group.name,
      creatorId: group.creator_user_id,
      memberCount: countRow?.count ?? 0,
      role: member.role,
      createdAt: group.created_at,
      updatedAt: group.updated_at,
    };
  }

  async getMembers(): Promise<GroupMemberSummary[]> {
    const rows = this.ctx.storage.sql.exec(
      `SELECT user_id, role, joined_at
       FROM group_members
       WHERE is_active = 1
       ORDER BY joined_at ASC`
    ).toArray() as any[];

    return rows.map((r) => ({
      userId: r.user_id,
      role: r.role,
      joinedAt: r.joined_at,
    }));
  }

  async isMember(userId: string): Promise<boolean> {
    const row = this.firstRow(
      `SELECT 1 FROM group_members WHERE user_id = ? AND is_active = 1`,
      userId
    );
    return row !== null;
  }

  async getMemberRole(userId: string): Promise<string | null> {
    const row = this.firstRow(
      `SELECT role FROM group_members WHERE user_id = ? AND is_active = 1`,
      userId
    );
    return row ? row.role : null;
  }

  async getActiveMemberIds(): Promise<string[]> {
    const rows = this.ctx.storage.sql.exec(
      `SELECT user_id FROM group_members WHERE is_active = 1`
    ).toArray() as any[];
    return rows.map((r) => r.user_id);
  }

  async addMember(actorId: string, targetUserId: string): Promise<{ success: boolean; error?: string }> {
    const callerRole = await this.getMemberRole(actorId);
    if (!callerRole || (callerRole !== "CREATOR" && callerRole !== "ADMIN")) {
      return { success: false, error: "Only creator or admins can add members" };
    }

    if (!ProtocolLimits.USER_ID_REGEX.test(targetUserId)) {
      return { success: false, error: "Invalid user ID format" };
    }

    const countRow = this.firstRow(
      `SELECT COUNT(*) as count FROM group_members WHERE is_active = 1`
    );
    if ((countRow?.count ?? 0) >= ProtocolLimits.MAX_GROUP_MEMBERS) {
      return { success: false, error: `Group cannot have more than ${ProtocolLimits.MAX_GROUP_MEMBERS} members` };
    }

    const existing = this.firstRow(
      `SELECT is_active FROM group_members WHERE user_id = ?`,
      targetUserId
    );

    const now = Date.now();
    if (existing) {
      if (existing.is_active === 1) {
        return { success: false, error: "User is already a member" };
      }
      this.ctx.storage.sql.exec(
        `UPDATE group_members SET is_active = 1, role = 'MEMBER', joined_at = ?, left_at = NULL WHERE user_id = ?`,
        now,
        targetUserId
      );
    } else {
      this.ctx.storage.sql.exec(
        `INSERT INTO group_members (group_id, user_id, role, joined_at, is_active)
         SELECT group_id, ?, 'MEMBER', ?, 1 FROM groups LIMIT 1`,
        targetUserId,
        now
      );
    }

    this.ctx.storage.sql.exec(`UPDATE groups SET updated_at = ?`, now);
    return { success: true };
  }

  async removeMember(actorId: string, targetUserId: string): Promise<{ success: boolean; error?: string }> {
    const callerRole = await this.getMemberRole(actorId);
    if (!callerRole || (callerRole !== "CREATOR" && callerRole !== "ADMIN")) {
      return { success: false, error: "Only creator or admins can remove members" };
    }

    if (actorId === targetUserId) {
      return { success: false, error: "Admins cannot remove themselves; use leaveGroup instead" };
    }

    const targetRole = await this.getMemberRole(targetUserId);
    if (!targetRole) {
      return { success: false, error: "Target user is not a member" };
    }

    if (targetRole === "CREATOR") {
      return { success: false, error: "Cannot remove creator from group" };
    }

    const now = Date.now();
    this.ctx.storage.sql.exec(
      `UPDATE group_members SET is_active = 0, left_at = ? WHERE user_id = ?`,
      now,
      targetUserId
    );
    this.ctx.storage.sql.exec(`UPDATE groups SET updated_at = ?`, now);
    return { success: true };
  }

  async leaveGroup(userId: string): Promise<{ success: boolean; error?: string }> {
    const targetRole = await this.getMemberRole(userId);
    if (!targetRole) {
      return { success: false, error: "Not a member of this group" };
    }

    const now = Date.now();
    this.ctx.storage.sql.exec(
      `UPDATE group_members SET is_active = 0, left_at = ? WHERE user_id = ?`,
      now,
      userId
    );
    this.ctx.storage.sql.exec(`UPDATE groups SET updated_at = ?`, now);

    // If an admin or creator leaves, ensure the group has an active admin if active members remain
    if (targetRole === "CREATOR" || targetRole === "ADMIN") {
      const activeAdmins = this.ctx.storage.sql.exec(
        `SELECT user_id FROM group_members WHERE is_active = 1 AND (role = 'CREATOR' OR role = 'ADMIN')`
      ).toArray();

      if (activeAdmins.length === 0) {
        // Promote the earliest joined active member to ADMIN
        const nextAdmin = this.firstRow(
          `SELECT user_id FROM group_members WHERE is_active = 1 ORDER BY joined_at ASC LIMIT 1`
        );
        if (nextAdmin) {
          this.ctx.storage.sql.exec(
            `UPDATE group_members SET role = 'ADMIN' WHERE user_id = ?`,
            nextAdmin.user_id
          );
        }
      }
    }

    return { success: true };
  }

  async renameGroup(actorId: string, newName: string): Promise<{ success: boolean; error?: string }> {
    const callerRole = await this.getMemberRole(actorId);
    if (!callerRole || (callerRole !== "CREATOR" && callerRole !== "ADMIN")) {
      return { success: false, error: "Only creator or admins can rename the group" };
    }

    const trimmed = newName.trim();
    if (
      trimmed.length < ProtocolLimits.MIN_GROUP_NAME_LENGTH ||
      trimmed.length > ProtocolLimits.MAX_GROUP_NAME_LENGTH
    ) {
      return {
        success: false,
        error: `Group name must be 1-${ProtocolLimits.MAX_GROUP_NAME_LENGTH} characters`,
      };
    }

    const now = Date.now();
    this.ctx.storage.sql.exec(`UPDATE groups SET name = ?, updated_at = ?`, trimmed, now);
    return { success: true };
  }
}
