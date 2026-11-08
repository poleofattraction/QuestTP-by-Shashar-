# QuestTP

Плагін для Paper: телепортаційні плити з меню — точки інтересу та точки відправки гравців.

## Вимоги

- Paper 26.3
- Java 25

## Збірка

```
mvn clean package
```

Готовий jar буде в `target/QuestTP-2.1.0.jar`. Також jar збирається автоматично через GitHub Actions
(вкладка **Actions** → останній запуск → **Artifacts**).

## Команди

`/questtp <give|list|remove|reset|reload>`

Права: `questtp.eventmaker`, `questtp.admin`.
