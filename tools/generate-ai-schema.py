"""Генерирует src/main/resources/ai/assistant.v1.schema.json — схему ответа для Gemini.

Запуск из корня проекта: python tools/generate-ai-schema.py. После правки — AssistantV1SchemaContractTest.

Подмножество JSON Schema, которое обещает Gemini: type (с null), enum, properties, required,
additionalProperties, items, minItems/maxItems, minimum/maximum, format: date. Длины и шаблоны — только на сервере.
"""
import json
import os

DIFFICULTY = ["NOVICE", "APPRENTICE", "ADEPT", "EXPERT", "MASTER", "LEGENDARY"]
STATUS = ["ACTIVE", "COMPLETED", "CANCELLED"]


def nullable(t):
    return {"type": [t, "null"]}


STR = nullable("string")
DATE = {"type": ["string", "null"], "format": "date"}
INT = nullable("integer")
BOOL = nullable("boolean")


def enum(values):
    return {"type": ["string", "null"], "enum": values}


def obj(props, required=None, description=None):
    o = {"type": ["object", "null"], "properties": props, "additionalProperties": False}
    if required:
        o["type"] = "object"
        o["required"] = required
    if description:
        o["description"] = description
    return o


def arr(item, max_items=None):
    a = {"type": ["array", "null"], "items": item}
    if max_items:
        a["maxItems"] = max_items
    return a


def change(value):
    return obj({"value": value}, description="null — не менять; {value: x} — установить; {value: null} — очистить")


TASK_NODE = obj({
    "title": STR, "description": STR, "difficulty": enum(DIFFICULTY),
    "points": {"type": ["integer", "null"], "minimum": 1, "maximum": 1000},
    "startDate": DATE, "deadline": DATE,
})
PROJECT_NODE = obj({
    "ref": STR, "existing": STR, "title": STR, "description": STR, "deadline": DATE,
    "tasks": arr(TASK_NODE, 10),
})
GOAL_NODE = obj({
    "existing": STR, "title": STR, "description": STR,
    "projects": arr(PROJECT_NODE, 10), "tasks": arr(TASK_NODE, 10),
})
REWARD_NODE = obj({"title": STR, "description": STR, "cost": {"type": ["integer", "null"], "minimum": 1}})

TASK_SET = obj({
    "title": change(STR), "description": change(STR), "difficulty": change(enum(DIFFICULTY)),
    "points": change(INT), "startDate": change(DATE), "deadline": change(DATE),
    "project": change(STR), "goal": change(STR),
})
PROJECT_SET = obj({"title": change(STR), "description": change(STR), "deadline": change(DATE), "goal": change(STR)})
GOAL_SET = obj({"title": change(STR), "description": change(STR)})
REWARD_SET = obj({"title": change(STR), "description": change(STR), "cost": change(INT)})


def update(set_schema):
    return obj({"target": STR, "set": set_schema})


PLAN = obj({
    "create": obj({
        "goal": GOAL_NODE,
        "projects": arr(PROJECT_NODE, 10),
        "tasks": arr(TASK_NODE, 10),
        "rewards": arr(REWARD_NODE, 3),
    }),
    "update": obj({
        "tasks": arr(update(TASK_SET), 20),
        "projects": arr(update(PROJECT_SET), 20),
        "goals": arr(update(GOAL_SET), 20),
        "rewards": arr(update(REWARD_SET), 20),
    }),
    "delete": arr(obj({"target": STR}), 10),
})

QUERY = obj({
    "query": enum(["LIST_GOALS", "LIST_PROJECTS", "LIST_TASKS", "LIST_REWARDS", "LIST_PLANS", "LIST_REFLECTIONS"]),
    "goal": STR, "project": STR, "task": STR, "unassigned": BOOL, "status": enum(STATUS),
    "period": obj({"from": DATE, "to": DATE}),
})

SCHEMA = {
    "type": "object",
    "description": "Ход AI по контракту assistant.v1. Поля чужого outcome — null.",
    "properties": {
        "contractVersion": {"type": "string", "enum": ["assistant.v1"]},
        "outcome": {"type": "string", "enum": ["DATA_REQUEST", "PROPOSAL", "ANSWER", "CLARIFICATION_NEEDED", "REFUSED"]},
        "dataRequestReason": STR,
        "dataRequests": {"type": ["array", "null"], "items": QUERY, "minItems": 1, "maxItems": 3},
        "summary": STR,
        "answer": STR,
        "plan": PLAN,
        "clarificationQuestion": STR,
        "refusalReason": enum(["OFF_TOPIC", "UNSUPPORTED_OPERATION", "OTHER"]),
    },
    "required": ["contractVersion", "outcome"],
    "additionalProperties": False,
}

path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'ai', 'assistant.v1.schema.json')
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(SCHEMA, f, ensure_ascii=False, indent=2)
    f.write('\n')
print('ok', os.path.getsize(path))
