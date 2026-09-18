package com.example.milestone.auth;

/** 项目级角色；ADMIN 为全局运维主体，来自配置而非数据库。 */
public enum Role {
    PROJECT_PARTY,
    RIGHTS_HOLDER,
    VERIFIER,
    AUDITOR
}
