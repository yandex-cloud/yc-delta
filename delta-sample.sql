CREATE DATABASE demo1;

USE demo1;

-- Представление для генерации 1000 строк с последовательными числами 0-999
CREATE VIEW demo1_tab1k_v AS (
WITH tab100(a) AS (
  (SELECT 0 AS a) UNION ALL (SELECT 1) UNION ALL (SELECT 2) UNION ALL (SELECT 3) UNION ALL (SELECT 4) UNION ALL
  (SELECT 5) UNION ALL (SELECT 6) UNION ALL (SELECT 7) UNION ALL (SELECT 8) UNION ALL (SELECT 9) UNION ALL
  (SELECT 10) UNION ALL (SELECT 11) UNION ALL (SELECT 12) UNION ALL (SELECT 13) UNION ALL (SELECT 14) UNION ALL
  (SELECT 15) UNION ALL (SELECT 16) UNION ALL (SELECT 17) UNION ALL (SELECT 18) UNION ALL (SELECT 19) UNION ALL
  (SELECT 20) UNION ALL (SELECT 21) UNION ALL (SELECT 22) UNION ALL (SELECT 23) UNION ALL (SELECT 24) UNION ALL
  (SELECT 25) UNION ALL (SELECT 26) UNION ALL (SELECT 27) UNION ALL (SELECT 28) UNION ALL (SELECT 29) UNION ALL
  (SELECT 30) UNION ALL (SELECT 31) UNION ALL (SELECT 32) UNION ALL (SELECT 33) UNION ALL (SELECT 34) UNION ALL
  (SELECT 35) UNION ALL (SELECT 36) UNION ALL (SELECT 37) UNION ALL (SELECT 38) UNION ALL (SELECT 39) UNION ALL
  (SELECT 40) UNION ALL (SELECT 41) UNION ALL (SELECT 42) UNION ALL (SELECT 43) UNION ALL (SELECT 44) UNION ALL
  (SELECT 45) UNION ALL (SELECT 46) UNION ALL (SELECT 47) UNION ALL (SELECT 48) UNION ALL (SELECT 49) UNION ALL
  (SELECT 50) UNION ALL (SELECT 51) UNION ALL (SELECT 52) UNION ALL (SELECT 53) UNION ALL (SELECT 54) UNION ALL
  (SELECT 55) UNION ALL (SELECT 56) UNION ALL (SELECT 57) UNION ALL (SELECT 58) UNION ALL (SELECT 59) UNION ALL
  (SELECT 60) UNION ALL (SELECT 61) UNION ALL (SELECT 62) UNION ALL (SELECT 63) UNION ALL (SELECT 64) UNION ALL
  (SELECT 65) UNION ALL (SELECT 66) UNION ALL (SELECT 67) UNION ALL (SELECT 68) UNION ALL (SELECT 69) UNION ALL
  (SELECT 70) UNION ALL (SELECT 71) UNION ALL (SELECT 72) UNION ALL (SELECT 73) UNION ALL (SELECT 74) UNION ALL
  (SELECT 75) UNION ALL (SELECT 76) UNION ALL (SELECT 77) UNION ALL (SELECT 78) UNION ALL (SELECT 79) UNION ALL
  (SELECT 80) UNION ALL (SELECT 81) UNION ALL (SELECT 82) UNION ALL (SELECT 83) UNION ALL (SELECT 84) UNION ALL
  (SELECT 85) UNION ALL (SELECT 86) UNION ALL (SELECT 87) UNION ALL (SELECT 88) UNION ALL (SELECT 89) UNION ALL
  (SELECT 90) UNION ALL (SELECT 91) UNION ALL (SELECT 92) UNION ALL (SELECT 93) UNION ALL (SELECT 94) UNION ALL
  (SELECT 95) UNION ALL (SELECT 96) UNION ALL (SELECT 97) UNION ALL (SELECT 98) UNION ALL (SELECT 99)),
  tab1k(a) AS (SELECT x.a + 100*y.a AS a FROM tab100 x, tab100 y WHERE y.a<10)
SELECT CAST(x.a AS BIGINT) AS num FROM tab1k x);

-- Представление для генерации 1 миллиарда строк с последовательными числами 1-1g
CREATE VIEW demo1_tab1g_v AS (
  SELECT 1 + x.num + 1000*y.num + 1000000*z.num AS num 
  FROM demo1_tab1k_v x, demo1_tab1k_v y, demo1_tab1k_v z
);

-- Представление для генерации выходного набора записей
CREATE VIEW demo1_uuid1g_v AS (
  SELECT num, tv, 
     extract(YEAR FROM tv) AS tv_year,
     extract(MONTH FROM tv) AS tv_month,
     extract(DAY FROM tv) AS tv_day,
     a, b, c, d
  FROM (SELECT x.num,
          y.tv_now - make_interval(0,0,0,0,0,0,x.num) AS tv,
          uuid() AS a, uuid() AS b, uuid() AS c, uuid() AS d 
        FROM demo1_tab1g_v x INNER JOIN (SELECT Now() AS tv_now) y ON 1=1)
);

-- Выходная таблица
CREATE TABLE deltatab1 (
  num bigint not null,
  tv timestamp not null,
  a varchar(40) not null,
  b varchar(40) not null,
  c varchar(40) not null,
  d varchar(40) not null,
  tv_year INT not null,
  tv_month INT not null,
  tv_day INT not null
) USING DELTA PARTITIONED BY (tv_year);

-- Оператор вставки
INSERT INTO deltatab1
SELECT num,COALESCE(tv,TIMESTAMP '1980-01-01 00:00:00') AS tv,a,b,c,d,tv_year,tv_month,tv_day
FROM demo1_uuid1g_v;

-- Пересборка данных для ускорения доступа
OPTIMIZE deltatab1;

-- Удалить лишние файлы
VACUUM deltatab2 RETAIN 0 HOURS;

-- Примеры запросов
select substring(a,1,1) as al, count(*) from deltatab1 group by substring(a,1,1) order by substring(a,1,1);
select substring(a,1,1) as al, count(*) from deltatab1 where b like '7f%' group by substring(a,1,1) order by substring(a,1,1);
