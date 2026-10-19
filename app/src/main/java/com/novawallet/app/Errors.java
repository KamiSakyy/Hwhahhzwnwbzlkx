package com.novawallet.app;
public class Errors{
 public static String human(Throwable e){
  String m=e==null?"неизвестная ошибка":String.valueOf(e.getMessage());
  String low=m.toLowerCase();
  String h;
  if(low.contains("insufficient")||low.contains("недостаточно")||low.contains("balance")) h="Недостаточно средств на оплату суммы и комиссии сети (газа). Пополните баланс SepoliaETH — тестовые монеты бесплатно дают краны на экране создания токена.";
  else if(low.contains("invalid address")||low.contains("bad address")||low.contains("неверный адрес")) h="Неверный адрес получателя. Адрес EVM должен начинаться с 0x и содержать 42 символа.";
  else if(low.contains("nonce")) h="Приложение использовало уже занятый номер транзакции (nonce). Ваши деньги не пропали: сеть отклонила повтор, а предыдущая транзакция с этим номером уже существует. В новой версии приложение само берёт следующий свободный номер и повторяет отправку — просто нажмите «Отправить» ещё раз.";
  else if(low.contains("gas")||low.contains("intrinsic")) h="Сети не хватило лимита газа для этой операции. Попробуйте ещё раз — приложение поднимет лимит автоматически.";
  else if(low.contains("timeout")||low.contains("failed to connect")||low.contains("unable to resolve")||low.contains("network")||low.contains("rpc")) h="Нет связи с сетью блокчейна. Проверьте интернет или VPN: некоторые сети блокируют RPC. Приложение уже пробует несколько резервных серверов — нажмите отправку ещё раз.";
  else if(low.contains("replacement")||low.contains("already known")) h="Такая транзакция уже отправлена и ожидает подтверждения. Проверьте историю по хэшу, не отправляйте повторно.";
  else if(low.contains("reject")||low.contains("denied")) h="Операция отклонена.";
  else h="Не удалось выполнить операцию. Ниже — технические детали, их можно скопировать и отправить в поддержку.";
  return h+"\n\nТехнические детали: "+m;
 }
}
