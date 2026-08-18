package com.example.facturacionapp


import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextPaint
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.registerForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.facturacionapp.databinding.ActivityMainBinding
import com.example.facturacionapp.db.Cliente
import com.example.facturacionapp.db.Factura
import com.example.facturacionapp.db.FacturacionDataBase
import com.example.facturacionapp.db.Producto
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.jar.Manifest

class MainActivity : AppCompatActivity() {

    companion object {
        lateinit var database: FacturacionDataBase
    }

    private lateinit var editRnc: EditText
    private lateinit var editNombreCliente: EditText
    private lateinit var editProductoId: EditText
    private lateinit var editCantidad: EditText
    private lateinit var btnProcesar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        editRnc = findViewById(R.id.editRnc)
        editNombreCliente = findViewById(R.id.editNombreCliente)
        editProductoId = findViewById(R.id.editProductoId)
        editCantidad = findViewById(R.id.editCantidad)
        btnProcesar = findViewById(R.id.btnProcesar)

        // Insertar un producto de prueba inicial en segundo plano
        insertarProductoDemo()

        btnProcesar.setOnClickListener {
            procesarFactura()
        }
    }

    private fun procesarFactura() {

        val rnc = editRnc.text.toString()
        val nombreCliente = editNombreCliente.text.toString()
        val prodIdStr = editProductoId.text.toString()
        val cantStr = editCantidad.text.toString()

        if (rnc.isEmpty() || nombreCliente.isEmpty() || prodIdStr.isEmpty() || cantStr.isEmpty()) {
            Toast.makeText(this, "Complete todos los campos", Toast.LENGTH_SHORT).show()
            return
        }

        val prodId = prodIdStr.toInt()
        val cantidad = cantStr.toInt()
        val rncid = rnc.toInt()

        // Ejecutar consulta SQLite en hilo secundario para no bloquear UI
        Thread {
            val dao = FacturacionDataBase.getDataBase(this).facturacionDao()
            val producto = dao.getProductoById(prodId)


            if (producto == null) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Producto no encontrado", Toast.LENGTH_SHORT)
                        .show()
                }
                return@Thread
            }

            if (producto.stock < cantidad) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Stock insuficiente(${producto.stock} disponibles)",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                return@Thread
            }


            // Registrar/Actualizar cliente
            val cliente = Cliente(rncid, nombreCliente, "Dirección genérica")
            dao.insertCliente(cliente)

            // Calcular Total y Actualizar Stock
            val total = producto.precio * cantidad
            producto.stock -= cantidad

            dao.updateProducto(producto)

            //Registrar Factura
            val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

            val factura = Factura(
                rncCedula = rncid,
                idProducto = prodId,
                cantidad = cantidad,
                Total = total,
                fecha = fecha
            )

            dao.insertFactura(factura)

            // Volver al hilo de la UI para mostrar resultado
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "¡Factura Creada! Total: $$total",
                    Toast.LENGTH_SHORT
                ).show()

                GenerarPdf(factura,producto,cliente)
                limpiarCampos()
            }
        }.start()


    }

    private fun insertarProductoDemo() {
        Thread {
            val dao = FacturacionDataBase.getDataBase(this).facturacionDao()
            if (dao.getProductoById(1) == null) {
                dao.insertProducto(
                    Producto(
                        idProducto = 1,
                        nombre = "Laptop",
                        precio = 500.0,
                        stock = 10
                    )
                )
            }
        }.start()
    }

    private fun limpiarCampos() {
        editRnc.text.clear()
        editNombreCliente.text.clear()
        editProductoId.text.clear()
        editCantidad.text.clear()
    }

    private fun showToast(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }
    private fun GenerarPdf(factura: Factura, producto: Producto, cliente: Cliente) {

        val docFolder =
            File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString())
        if(!docFolder.exists()){
            docFolder.mkdir()
        }

        //getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString()
        //DIRECTORY_DOCUMENTS

        val pdfDocument = PdfDocument()

        // Tamaño de la página
        val pageInfo = PdfDocument.PageInfo.Builder(
            595, // Ancho
            842, // Alto
            1 ).create()

        val page = pdfDocument.startPage(pageInfo)

        val canvas = page.canvas

        // Configuración de Paint
        val paint = Paint()
        paint.color = android.graphics.Color.BLACK
        paint.textSize = 14f

        // ========================= // ENCABEZADO // =========================

        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 24f
        canvas.drawText( "FACTURA", 230f, 50f, paint )

        paint.typeface = Typeface.DEFAULT
        paint.textSize = 14f
        canvas.drawText( "RNC: ${factura.rncCedula}", 50f, 90f, paint )

        canvas.drawText( "Cliente: ${cliente.nombre}", 50f, 115f, paint )

        // ========================= // TABLA DE PRODUCTOS // =========================
        val inicioX = 50f
        val inicioY = 160f
        val altoFila = 35f

        // Ancho de las columnas
        val columnaId = 60f
        val columnaNombre = 220f
        val columnaCantidad = 90f
        val columnaPrecio = 100f

        // Posiciones X
        val xId = inicioX
        val xNombre = xId + columnaId
        val xCantidad = xNombre + columnaNombre
        val xPrecio = xCantidad + columnaCantidad

        // Bordes
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f

        // Encabezado
        canvas.drawRect( inicioX, inicioY, xNombre, inicioY + altoFila, paint )

        canvas.drawRect( xNombre, inicioY, xCantidad, inicioY + altoFila, paint )
        canvas.drawRect( xCantidad, inicioY, xPrecio, inicioY + altoFila, paint )
        canvas.drawRect( xPrecio, inicioY, xPrecio + columnaPrecio, inicioY + altoFila, paint )

        // Texto del encabezado
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f
        canvas.drawText( "ID", xId + 10f, inicioY + 22f, paint )

        canvas.drawText( "Producto", xNombre + 10f, inicioY + 22f, paint )

        canvas.drawText( "Cantidad", xCantidad + 10f, inicioY + 22f, paint )

        canvas.drawText( "Precio", xPrecio + 10f, inicioY + 22f, paint )


        // ========================= // PRODUCTOS // =========================
        paint.typeface = Typeface.DEFAULT
        var y = inicioY + altoFila
        var total = 0.0


        val subtotal = factura.Total

        // Dibujar bordes de la fila
        paint.style = Paint.Style.STROKE
        canvas.drawRect( inicioX, y, xNombre, y + altoFila, paint )

        canvas.drawRect( xNombre, y, xCantidad, y + altoFila, paint )

        canvas.drawRect( xCantidad, y, xPrecio, y + altoFila, paint )

        canvas.drawRect( xPrecio, y, xPrecio + columnaPrecio, y + altoFila, paint )

        // Texto
        paint.style = Paint.Style.FILL

        canvas.drawText(producto.idProducto.toString(), xId + 10f, y + 22f, paint )

        canvas.drawText(producto.nombre, xNombre + 10f, y + 22f, paint )

        canvas.drawText(factura.cantidad.toString(), xCantidad + 25f, y + 22f, paint )

        canvas.drawText(String.format("%.2f", producto.precio), xPrecio + 10f, y + 22f, paint )
        y += altoFila


        // ========================= // TOTAL // =========================

        y += 30f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 16f
        canvas.drawText( "TOTAL:", 400f, y, paint )

        canvas.drawText( String.format("%.2f", factura.Total), 480f, y, paint )

        // Finalizar página
        pdfDocument.finishPage(page)

        // Guardar PDF
        //FileOutputStream(archivo).use { outputStream -> pdfDocument.writeTo(outputStream) }

        val file = File(docFolder.absoluteFile,"archivo.pdf")

        try {
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(this,"PDF CREADO", Toast.LENGTH_SHORT).show()
        }
        catch (e: Exception){
            e.printStackTrace()
        }

        // Cerrar documento
        pdfDocument.close()


    }

}