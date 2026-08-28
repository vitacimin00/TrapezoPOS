package com.trapezo.pos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.CustomerEntity
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(canManage: Boolean) {
    val scope=rememberCoroutineScope();var query by remember{mutableStateOf("")};var customers by remember{mutableStateOf(emptyList<CustomerEntity>())};var total by remember{mutableStateOf(0)};var form by remember{mutableStateOf<CustomerEntity?>(null)};var add by remember{mutableStateOf(false)};var notice by remember{mutableStateOf<String?>(null)}
    fun refresh(){scope.launch{val r=AppGraph.customers.page(query);customers=r.first;total=r.second}}
    LaunchedEffect(query){refresh()}
    Scaffold(topBar={TopAppBar(title={Text("Customer",fontWeight=FontWeight.Bold)},actions={if(canManage)IconButton(onClick={add=true}){Icon(Icons.Default.Add,"Tambah customer")}})}){pad->Column(Modifier.fillMaxSize().padding(pad).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){notice?.let{CustomerNotice(it){notice=null}};OutlinedTextField(query,{query=it},label={Text("Cari nama, HP, atau kode")},singleLine=true,modifier=Modifier.fillMaxWidth());Text("$total customer")
        if(customers.isEmpty()) Text("Belum ada customer.",Modifier.padding(16.dp)) else LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp)){items(customers,key={it.id}){c->Card(Modifier.fillMaxWidth().clickable{if(canManage)form=c}){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(c.name,fontWeight=FontWeight.SemiBold);Text("${c.code} • ${c.phone}",style=MaterialTheme.typography.bodySmall);Text("Poin ${c.points} • Saldo ${Money.fmt(c.balance)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)};if(canManage)Icon(Icons.Default.Edit,"Edit")}}}}
    }}
    if(add) CustomerEditor(null,onDismiss={add=false},onSave={scope.launch{val r=AppGraph.customers.save(it);if(r.error==null){add=false;notice="Customer tersimpan";refresh()}else notice=r.error}})
    form?.let{existing->CustomerEditor(existing,onDismiss={form=null},onSave={scope.launch{val r=AppGraph.customers.save(it);if(r.error==null){form=null;notice="Customer diperbarui";refresh()}else notice=r.error}},onDelete={scope.launch{AppGraph.customers.delete(existing);form=null;notice="Customer dihapus";refresh()}})}
}
@Composable private fun CustomerNotice(text:String,dismiss:()->Unit)=Card(Modifier.fillMaxWidth()){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f));TextButton(onClick=dismiss){Text("Tutup")}}}
@Composable private fun CustomerEditor(existing:CustomerEntity?,onDismiss:()->Unit,onSave:(CustomerEntity)->Unit,onDelete:(()->Unit)?=null){var code by remember{mutableStateOf(existing?.code.orEmpty())};var name by remember{mutableStateOf(existing?.name.orEmpty())};var phone by remember{mutableStateOf(existing?.phone.orEmpty())};var email by remember{mutableStateOf(existing?.email.orEmpty())};var address by remember{mutableStateOf(existing?.address.orEmpty())};var points by remember{mutableStateOf(existing?.points?.toString()?:"0")};var balance by remember{mutableStateOf(existing?.balance?.toString()?:"0")}
AlertDialog(onDismissRequest=onDismiss,title={Text(if(existing==null)"Tambah customer"else"Edit customer")},text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){OutlinedTextField(code,{code=it},label={Text("Kode (kosong = otomatis)")},singleLine=true);OutlinedTextField(name,{name=it},label={Text("Nama *")},singleLine=true);OutlinedTextField(phone,{phone=it},label={Text("Nomor HP")},singleLine=true);OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true);OutlinedTextField(address,{address=it},label={Text("Alamat")});OutlinedTextField(points,{points=it},label={Text("Poin")},singleLine=true);OutlinedTextField(balance,{balance=it},label={Text("Saldo")},singleLine=true)}},confirmButton={Button(onClick={onSave(CustomerEntity(id=existing?.id?:0,code=code,name=name,phone=phone,email=email,address=address,points=Money.parse(points),balance=Money.parse(balance),createdAt=existing?.createdAt?:System.currentTimeMillis()))},enabled=name.isNotBlank()){Text("SIMPAN")}},dismissButton={Row{onDelete?.let{TextButton(onClick=it){Text("HAPUS")}};TextButton(onClick=onDismiss){Text("BATAL")}}})}
