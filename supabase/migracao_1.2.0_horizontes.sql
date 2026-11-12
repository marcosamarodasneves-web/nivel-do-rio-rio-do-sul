-- =====================================================================
-- Nível do Rio 1.2.0 — previsões de 1, 2 e 3 horas
-- Cole este arquivo inteiro no SQL Editor do projeto Supabase e execute UMA vez.
-- Pode ser executado de novo sem problema (todos os comandos são idempotentes).
--
-- O que muda:
--   * nova coluna horizon_h (1, 2 ou 3 horas à frente); as linhas antigas viram horizon_h = 1;
--   * a chave única passa a ser (bridge, target_time, horizon_h);
--   * a visão river_forecasts_accuracy passa a separar o aproveitamento por horizonte.
--
-- Atenção: depois desta migração, o app 1.1.0 deixa de conseguir enviar previsões
-- (ele usa a chave única antiga). Atualize os aparelhos para a 1.2.0.
-- Antes da migração, a 1.2.0 continua enviando só a previsão de +1 h e guarda as de +2 h e +3 h
-- na fila do aparelho; elas sobem sozinhas na primeira atualização depois da migração.
-- =====================================================================

alter table public.river_forecasts
    add column if not exists horizon_h smallint not null default 1;

alter table public.river_forecasts
    drop constraint if exists river_forecasts_horizon_h_check;
alter table public.river_forecasts
    add constraint river_forecasts_horizon_h_check check (horizon_h between 1 and 3);

-- Chave única antiga (bridge, target_time) → nova (bridge, target_time, horizon_h).
alter table public.river_forecasts
    drop constraint if exists river_forecasts_bridge_target_time_key;
alter table public.river_forecasts
    drop constraint if exists river_forecasts_bridge_target_time_horizon_key;
alter table public.river_forecasts
    add constraint river_forecasts_bridge_target_time_horizon_key unique (bridge, target_time, horizon_h);

drop index if exists public.river_forecasts_bridge_time_idx;
create index if not exists river_forecasts_bridge_horizon_time_idx
    on public.river_forecasts (bridge, horizon_h, target_time desc);

comment on column public.river_forecasts.horizon_h is
    'Quantas horas à frente a previsão foi feita (1, 2 ou 3).';

-- Visão de acompanhamento, agora separada por horizonte.
drop view if exists public.river_forecasts_accuracy;
create view public.river_forecasts_accuracy as
select
    bridge,
    horizon_h,
    date_trunc('day', target_time at time zone 'America/Sao_Paulo')::date as dia,
    count(*)                                                              as previsoes,
    count(*) filter (where outcome <> 'pendente')                         as conferidas,
    count(*) filter (where outcome = 'dentro')                            as dentro_da_margem,
    count(*) filter (where outcome = 'quase')                             as quase,
    count(*) filter (where outcome = 'fora')                              as fora,
    count(*) filter (where outcome = 'sem_medicao')                       as sem_medicao,
    round(100.0 * count(*) filter (where outcome = 'dentro')
          / nullif(count(*) filter (where outcome in ('dentro','quase','fora')), 0))
                                                                          as pct_dentro,
    round(avg(abs(error_cm)) filter (where outcome in ('dentro','quase','fora'))::numeric, 1)
                                                                          as erro_medio_cm
from public.river_forecasts
group by 1, 2, 3
order by 3 desc, 1, 2;

grant select on public.river_forecasts_accuracy to anon, authenticated;

-- Faz a API REST (PostgREST) enxergar a coluna nova na hora, sem esperar o recarregamento automático.
notify pgrst, 'reload schema';

-- Conferência rápida depois de rodar:
--   select bridge, horizon_h, count(*) from public.river_forecasts group by 1, 2 order by 1, 2;
--   select * from public.river_forecasts_accuracy order by dia desc, bridge, horizon_h;
